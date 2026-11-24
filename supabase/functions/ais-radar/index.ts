import WebSocket from "npm:ws@8.18.0";
import { Buffer } from "node:buffer";

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
  shipType: number;
  imo: number;
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
  const radiusKm = Math.min(100, Math.max(5, Number(url.searchParams.get("radius") || 20)));
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    return json({ error: "Koordinat GPS tidak valid" }, 400);
  }

  const latDelta = radiusKm / 111;
  const lonDelta = radiusKm / (111 * Math.max(0.15, Math.cos(lat * Math.PI / 180)));
  const bounds = [[
    [Math.max(-90, lat - latDelta), Math.max(-180, lon - lonDelta)],
    [Math.min(90, lat + latDelta), Math.min(180, lon + lonDelta)],
  ]];

  // AISStream is WebSocket-only. Do not issue a plain HTTPS GET probe:
  // it adds latency and can time out even when the WebSocket endpoint is usable.

  const ships = new Map<number, Ship>();
  const names = new Map<number, string>();
  const staticDataByMmsi = new Map<number, { shipType: number; imo: number }>();
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

      // Collect a useful live sample while keeping the HTTP request short-lived.
      const timer = setTimeout(() => finish(), 35000);
      try {
        // AISStream requires permessage-deflate for full message bandwidth.
        socket = new WebSocket("wss://stream.aisstream.io/v0/stream", {
          perMessageDeflate: true,
          handshakeTimeout: 20000,
        });

        socket.on("open", () => {
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
        });

        socket.on("message", (data: unknown) => {
          try {
            const raw = typeof data === "string"
              ? data
              : Buffer.isBuffer(data)
                ? data.toString("utf8")
                : Array.isArray(data)
                  ? Buffer.concat(data).toString("utf8")
                  : data instanceof ArrayBuffer
                    ? new TextDecoder().decode(data)
                    : String(data);
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
              const staticData = message.ShipStaticData || message.StaticDataReport || {};
              const name = String(staticData.Name || meta.ShipName || "").replace(/@/g, "").trim();
              const shipType = Number(staticData.Type ?? 0);
              const imo = Number(staticData.ImoNumber ?? staticData.IMO ?? 0);
              staticDataByMmsi.set(mmsi, { shipType, imo });
              if (name) names.set(mmsi, name);
              const existing = ships.get(mmsi);
              if (existing) {
                if (name) existing.name = name;
                existing.shipType = shipType;
                existing.imo = imo;
              }
              return;
            }

            if (!["PositionReport", "StandardClassBPositionReport", "ExtendedClassBPositionReport"].includes(messageType)) return;
            const shipLat = Number(meta.Latitude ?? payload.Latitude);
            const shipLon = Number(meta.Longitude ?? payload.Longitude);
            if (!Number.isFinite(shipLat) || !Number.isFinite(shipLon) ||
                Math.abs(shipLat) > 90 || Math.abs(shipLon) > 180) return;

            const previous = ships.get(mmsi);
            const staticInfo = staticDataByMmsi.get(mmsi);
            ships.set(mmsi, {
              mmsi,
              name: names.get(mmsi) || String(meta.ShipName || previous?.name || "Nama kapal belum diterima").replace(/@/g, "").trim(),
              latitude: shipLat,
              longitude: shipLon,
              speedKnots: Number(payload.Sog ?? 0),
              course: Number(payload.Cog ?? payload.TrueHeading ?? 0),
              heading: Number(payload.TrueHeading ?? payload.Cog ?? 0),
              shipType: staticInfo?.shipType ?? previous?.shipType ?? 0,
              imo: staticInfo?.imo ?? previous?.imo ?? 0,
              receivedAt: new Date().toISOString(),
            });
          } catch (_) {
            // Ignore malformed packets but continue consuming the stream.
          }
        });

        socket.on("error", (error: Error) => finish(error.message || "Koneksi AISStream gagal"));
        socket.on("close", (code: number, reason: Buffer) => {
          closeCode = code;
          closeReason = reason?.toString("utf8") || "";
          finish();
        });
      } catch (error) {
        finish(error instanceof Error ? error.message : "Tidak dapat membuka koneksi AISStream");
      }
    });

    const shipList = result.ships;
    const message = shipList.length
      ? null
      : !subscriptionConfirmed
        ? "AISStream belum mengonfirmasi langganan. Periksa API key AISSTREAM_API_KEY."
        : compressionEnabled !== true
          ? "AISStream menerima koneksi tetapi kompresi permessage-deflate tidak aktif."
          : "Langganan dan kompresi AISStream aktif, tetapi belum ada laporan posisi kapal pada jendela pemantauan ini. Periksa cakupan penerima AIS di area tersebut.";

    const connectionDiagnostic = result.streamError
      ? "Koneksi WebSocket AISStream gagal: " + result.streamError
      : subscriptionConfirmed
        ? "WebSocket tersambung dan langganan AIS dikonfirmasi."
        : "WebSocket terbuka, tetapi konfirmasi langganan AIS belum diterima.";

    return json({
      ships: shipList,
      count: shipList.length,
      radiusKm,
      listeningSeconds: 45,
      subscriptionConfirmed,
      compressionEnabled,
      messageCounts,
      closeCode,
      closeReason,
      connectionDiagnostic,
      streamError: result.streamError || null,
      message,
    });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Gagal mengambil data AIS" }, 502);
  }
});

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
