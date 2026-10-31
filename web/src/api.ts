export type Pt = [number, number];

export type City = {
  width: number;
  height: number;
  nodes: Pt[];
  roads: { a: number; b: number; kind: "avenue" | "street" | "old_town" | "bridge" | "path" }[];
  merchants: { id: string; name: string; kind: string; x: number; y: number }[];
  areas: { kind: "river" | "park" | "oldtown"; outline: Pt[] }[];
  depot: Pt;
};

export type Stop = { orderId: string; kind: "pickup" | "dropoff"; label: string; x: number; y: number; arrival: number };

export type Courier = {
  id: string;
  name: string;
  vehicle: "bike" | "van";
  capacity: number;
  load: number;
  status: "idle" | "driving" | "at_stop" | "waiting_for_phone";
  phone: boolean;
  x: number;
  y: number;
  route: Pt[];
  stops: Stop[];
  busyShare: number;
};

export type Order = {
  id: string;
  merchant: string;
  merchantId: string;
  customer: string;
  address: string;
  size: number;
  status: "waiting" | "assigned" | "picked_up" | "delivered";
  courierId: string | null;
  createdAt: number;
  promisedBy: number;
  eta: number | null;
  pickedUpAt: number | null;
  deliveredAt: number | null;
  late: boolean;
  pinned: boolean;
  pickup: Pt;
  dropoff: Pt;
};

export type Snapshot = {
  clock: number;
  speed: number;
  paused: boolean;
  autoDispatch: boolean;
  demand: "calm" | "steady" | "rush";
  couriers: Courier[];
  orders: Order[];
  kpis: { active: number; waiting: number; delivered: number; onTimePercent: number; averageMinutes: number; busyCouriers: number };
  events: { at: number; kind: string; text: string }[];
};

export type Me = { user: string; role: "dispatcher" | "courier" };

export type Report = { courierId: string; delivered: number; onTime: number; averageMinutes: number }[];

export class Unauthorized extends Error {}

// Every change carries this header; the server refuses writes without it (see RequireAppHeader.java).
const WRITE = { "X-Requested-With": "waypoint", "Content-Type": "application/json" };

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: "same-origin", ...init });
  if (res.status === 401) throw new Unauthorized();
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.error ?? body?.violations?.[0]?.message ?? `The server said ${res.status}`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

const send = <T,>(method: string, path: string, body?: unknown) =>
  call<T>(path, { method, headers: WRITE, body: body === undefined ? undefined : JSON.stringify(body) });

export const api = {
  me: () => call<Me>("/api/me"),
  city: () => call<City>("/api/city"),
  report: () => call<Report>("/api/report"),

  login: async (user: string, password: string) => {
    const res = await fetch("/j_security_check", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ j_username: user, j_password: password }),
    });
    if (!res.ok) throw new Error(res.status === 401 ? "That username and password don't match." : `Sign-in failed (${res.status})`);
  },
  logout: () => send<void>("POST", "/api/logout"),

  settings: (s: Partial<{ autoDispatch: boolean; demand: string; speed: number; paused: boolean }>) =>
    send<void>("PUT", "/api/settings", { ...s, demand: s.demand?.toUpperCase() }),
  assign: (orderId: string, courierId: string) =>
    send<{ courierId: string; extraMinutes: number }>("POST", `/api/orders/${orderId}/assign`, { courierId }),
  createOrder: (o: { merchantId: string; customer: string; x: number; y: number; size: number }) =>
    send<{ id: string }>("POST", "/api/orders", o),
  phone: (courierId: string, on: boolean) => send<void>("PUT", `/api/couriers/${courierId}/phone`, { on }),
  complete: (courierId: string) => send<{ orderId: string; kind: string }>("POST", `/api/couriers/${courierId}/complete`),
};

/** Opens the live feed and reconnects with backoff when it drops. Returns a function that closes it. */
export function live(onSnapshot: (s: Snapshot) => void, onState: (connected: boolean) => void): () => void {
  let socket: WebSocket | null = null;
  let closed = false;
  let retry = 500;
  const open = () => {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    socket = new WebSocket(`${proto}://${location.host}/ws/live`);
    socket.onopen = () => {
      retry = 500;
      onState(true);
    };
    socket.onmessage = (e) => onSnapshot(JSON.parse(e.data));
    socket.onclose = () => {
      onState(false);
      if (!closed) setTimeout(open, (retry = Math.min(retry * 2, 8000)));
    };
  };
  open();
  return () => {
    closed = true;
    socket?.close();
  };
}

export function clock(seconds: number): string {
  const m = Math.round(seconds / 60);
  return `${String(Math.floor(m / 60) % 24).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

export function minutes(seconds: number): string {
  const m = Math.round(seconds / 60);
  return m <= 0 ? "now" : `${m} min`;
}

/** A steady colour per courier, used on the map and in lists. */
export const COURIER_HUES: Record<string, number> = {
  lucia: 8, marco: 210, aitana: 160, hugo: 280, nerea: 35, iker: 190, carmen: 330, dani: 120, sofia: 255, pablo: 60,
};
export const hue = (id: string | null | undefined) => (id ? COURIER_HUES[id] ?? 200 : 200);
