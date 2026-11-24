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
  const messageCounts: Record<string, number> = {};
  let socket: WebSocket | null = null;
  let subscriptionConfirmed = false;
  let compressionEnabled: boolean | null = null;
  let closeCode: number | null = null;
  let closeReason = "";

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

      // A 20-second sample was too short for an event-driven feed at some ports.
      const timer = setTimeout(() => finish(), 30000);
      try {
        socket = new WebSocket("wss://stream.aisstream.io/v0/stream");
        socket.binaryType = "arraybuffer";
        socket.onopen = () => {
          socket?.send(JSON.stringify({
            APIKey: apiKey,
            BoundingBoxes: bounds,
            FilterMessageTypes: [
              "PositionReport",
              "StandardClassBPositionReport",
              "ExtendedClassBPositionReport",
              "ShipStaticData",
              "StaticDataReport",
            ],
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
            const messageType = String(packet.MessageType || "Unknown");
            messageCounts[messageType] = (messageCounts[messageType] || 0) + 1;

            if (messageType === "SubscriptionConfirmation") {
              subscriptionConfirmed = true;
              compressionEnabled = packet.Message?.CompressionEnabled === true;
              return;
            }

            const meta = packet.MetaData || {};
            const message = packet.Message || {};
            const payload =
              message.PositionReport ||
              message.StandardClassBPositionReport ||
              message.ExtendedClassBPositionReport ||
              message.StaticDataReport ||
              message.ShipStaticData ||
              {};
            const mmsi = Number(meta.MMSI ?? payload.UserID);
            if (!Number.isFinite(mmsi)) return;

            if (messageType === "ShipStaticData" || messageType === "StaticDataReport") {
              const name = String(payload.Name || meta.ShipName || "").replace(/@/g, "").trim();
              if (name) {
                names.set(mmsi, name);
                const existing = ships.get(mmsi);
                if (existing) existing.name = name;
              }
              return;
            }

            if (!["PositionReport", "StandardClassBPositionReport", "ExtendedClassBPositionReport"].includes(messageType)) return;
            const shipLat = Number(meta.Latitude ?? payload.Latitude);
            const shipLon = Number(meta.Longitude ?? payload.Longitude);
            if (!Number.isFinite(shipLat) || !Number.isFinite(shipLon) ||
                Math.abs(shipLat) > 90 || Math.abs(shipLon) > 180) return;
            const previous = ships.get(mmsi);
            ships.set(mmsi, {
              mmsi,
              name: names.get(mmsi) || String(meta.ShipName || previous?.name || "Nama kapal belum diterima").replace(/@/g, "").trim(),
              latitude: shipLat,
              longitude: shipLon,
              speedKnots: Number(payload.Sog ?? 0),
              course: Number(payload.Cog ?? payload.TrueHeading ?? 0),
              heading: Number(payload.TrueHeading ?? 0),
              receivedAt: new Date().toISOString(),
            });
          } catch (_) { /* Ignore malformed AIS messages. */ }
        };
        socket.onerror = () => finish("Koneksi AISStream gagal atau ditolak");
        socket.onclose = (event) => {
          closeCode = event.code;
          closeReason = event.reason || "";
          finish();
        };
      } catch (_) {
        finish("Tidak dapat membuka koneksi AISStream");
      }
    });

    const shipList = result.ships;
    const message = shipList.length
      ? null
      : !subscriptionConfirmed
        ? "AISStream belum mengonfirmasi langganan. Periksa API key AISSTREAM_API_KEY dan status koneksi."
        : compressionEnabled === false
          ? "Langganan diterima tetapi kompresi permessage-deflate tidak aktif; koneksi mungkin tidak menerima aliran data penuh."
          : "Langganan AISStream diterima, tetapi tidak ada laporan posisi kapal dalam 30 detik. Ini menunjukkan keterbatasan cakupan/penerimaan data AIS di area ini, bukan masalah tampilan peta.";

    return json({
      ships: shipList,
      count: shipList.length,
      radiusKm,
      listeningSeconds: 30,
      subscriptionConfirmed,
      compressionEnabled,
      messageCounts,
      closeCode,
      closeReason,
      streamError: result.streamError || null,
      message,
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
