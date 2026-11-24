const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

type Ship = {
  mmsi: number;
  name: string;
  latitude: number;
  longitude: number;
  speedKnots: number;
  course: number;
  heading: number;
  receivedAt: string;
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const apiKey = Deno.env.get("AISSTREAM_API_KEY");
  if (!apiKey) return json({ error: "AISSTREAM_API_KEY belum dipasang di Supabase Edge Function Secrets" }, 503);

  const url = new URL(req.url);
  const lat = Number(url.searchParams.get("lat"));
  const lon = Number(url.searchParams.get("lon"));
  const radiusKm = Math.min(100, Math.max(5, Number(url.searchParams.get("radius") || 50)));
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    return json({ error: "Koordinat GPS tidak valid" }, 400);
  }

  const latDelta = radiusKm / 111;
  const lonDelta = radiusKm / (111 * Math.max(0.15, Math.cos(lat * Math.PI / 180)));
  const bounds = [[
    [Math.max(-90, lat - latDelta), Math.max(-180, lon - lonDelta)],
    [Math.min(90, lat + latDelta), Math.min(180, lon + lonDelta)],
  ]];

  const ships = new Map<number, Ship>();
  const names = new Map<number, string>();
  let socket: WebSocket | null = null;

  try {
    const result = await new Promise<{ ships: Ship[]; streamError?: string }>((resolve) => {
      let finished = false;
      const finish = (streamError?: string) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        try { socket?.close(); } catch (_) {}
        resolve({ ships: [...ships.values()], ...(streamError ? { streamError } : {}) });
      };

      const timer = setTimeout(() => finish(), 20000);
      try {
        socket = new WebSocket("wss://stream.aisstream.io/v0/stream");
        socket.binaryType = "arraybuffer";
        socket.onopen = () => {
          socket?.send(JSON.stringify({
            APIKey: apiKey,
            BoundingBoxes: bounds,
            FilterMessageTypes: ["PositionReport", "ShipStaticData"],
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
            const meta = packet.MetaData || {};
            const mmsi = Number(meta.MMSI ?? packet.Message?.PositionReport?.UserID ?? packet.Message?.ShipStaticData?.UserID);
            if (!Number.isFinite(mmsi)) return;

            if (packet.MessageType === "ShipStaticData") {
              const staticData = packet.Message?.ShipStaticData || {};
              const name = String(staticData.Name || meta.ShipName || "").replace(/@/g, "").trim();
              if (name) {
                names.set(mmsi, name);
                const existing = ships.get(mmsi);
                if (existing) existing.name = name;
              }
              return;
            }

            if (packet.MessageType !== "PositionReport") return;
            const pos = packet.Message?.PositionReport || {};
            const shipLat = Number(meta.Latitude ?? pos.Latitude);
            const shipLon = Number(meta.Longitude ?? pos.Longitude);
            if (!Number.isFinite(shipLat) || !Number.isFinite(shipLon)) return;
            const previous = ships.get(mmsi);
            ships.set(mmsi, {
              mmsi,
              name: names.get(mmsi) || String(meta.ShipName || previous?.name || "Nama kapal belum diterima").replace(/@/g, "").trim(),
              latitude: shipLat,
              longitude: shipLon,
              speedKnots: Number(pos.Sog ?? 0),
              course: Number(pos.Cog ?? pos.TrueHeading ?? 0),
              heading: Number(pos.TrueHeading ?? 0),
              receivedAt: new Date().toISOString(),
            });
          } catch (_) { /* Ignore malformed AIS messages. */ }
        };
        socket.onerror = () => finish("Koneksi AISStream gagal");
        socket.onclose = () => finish();
      } catch (_) {
        finish("Tidak dapat membuka koneksi AISStream");
      }
    });
    return json({
      ships: result.ships,
      count: result.ships.length,
      radiusKm,
      listeningSeconds: 20,
      streamError: result.streamError || null,
      message: result.ships.length ? null : "Tidak ada siaran AIS yang diterima dalam jendela pemantauan ini. Coba perluas radius atau periksa cakupan AISStream.",
    });
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
