const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const apiKey = Deno.env.get("AISSTREAM_API_KEY");
  if (!apiKey) return json({ error: "AISSTREAM_API_KEY belum dipasang di Supabase Edge Function Secrets" }, 503);

  const url = new URL(req.url);
  const lat = Number(url.searchParams.get("lat"));
  const lon = Number(url.searchParams.get("lon"));
  const radiusKm = Math.min(50, Math.max(5, Number(url.searchParams.get("radius") || 20)));
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    return json({ error: "Koordinat GPS tidak valid" }, 400);
  }

  const latDelta = radiusKm / 111;
  const lonDelta = radiusKm / (111 * Math.max(0.15, Math.cos(lat * Math.PI / 180)));
  const bounds = [[
    [Math.max(-90, lat - latDelta), Math.max(-180, lon - lonDelta)],
    [Math.min(90, lat + latDelta), Math.min(180, lon + lonDelta)],
  ]];

  const ships = new Map<number, Record<string, unknown>>();
  let socket: WebSocket | null = null;

  try {
    const result = await new Promise<{ ships: Record<string, unknown>[]; streamError?: string }>((resolve) => {
      let finished = false;
      const finish = (streamError?: string) => {
        if (finished) return;
        finished = true;
        try { socket?.close(); } catch (_) {}
        resolve({ ships: [...ships.values()], ...(streamError ? { streamError } : {}) });
      };

      const timer = setTimeout(() => finish(), 7000);
      try {
        socket = new WebSocket("wss://stream.aisstream.io/v0/stream");
        socket.binaryType = "arraybuffer";
        socket.onopen = () => {
          socket?.send(JSON.stringify({
            APIKey: apiKey,
            BoundingBoxes: bounds,
            FilterMessageTypes: ["PositionReport"],
          }));
        };
        socket.onmessage = async (event) => {
          try {
            let raw: string;
            if (typeof event.data === "string") raw = event.data;
            else if (event.data instanceof ArrayBuffer) raw = new TextDecoder().decode(event.data);
            else if (event.data instanceof Blob) raw = await event.data.text();
            else return;
            const packet = JSON.parse(raw);
            if (packet.MessageType !== "PositionReport") return;
            const meta = packet.MetaData || {};
            const pos = packet.Message?.PositionReport || {};
            const shipLat = Number(meta.Latitude ?? pos.Latitude);
            const shipLon = Number(meta.Longitude ?? pos.Longitude);
            const mmsi = Number(meta.MMSI ?? pos.UserID);
            if (!Number.isFinite(shipLat) || !Number.isFinite(shipLon) || !Number.isFinite(mmsi)) return;
            ships.set(mmsi, {
              mmsi,
              name: String(meta.ShipName || "Kapal tidak dikenal").trim(),
              latitude: shipLat,
              longitude: shipLon,
              speedKnots: Number(pos.Sog ?? 0),
              course: Number(pos.Cog ?? pos.TrueHeading ?? 0),
              heading: Number(pos.TrueHeading ?? 0),
              receivedAt: new Date().toISOString(),
            });
          } catch (_) { /* Ignore malformed or non-position AIS messages. */ }
        };
        socket.onerror = () => { clearTimeout(timer); finish("Koneksi AISStream gagal"); };
        socket.onclose = () => { clearTimeout(timer); finish(); };
      } catch (_) {
        clearTimeout(timer);
        finish("Tidak dapat membuka koneksi AISStream");
      }
    });
    return json({ ships: result.ships, count: result.ships.length, radiusKm, streamError: result.streamError || null });
  } catch (_) {
    return json({ error: "Gagal mengambil data AIS" }, 502);
  }
});

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
