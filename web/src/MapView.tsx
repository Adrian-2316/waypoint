import { memo, useMemo, useRef, type MouseEvent } from "react";
import { hue, type City, type Courier, type Order, type Pt, type Snapshot } from "./api";

type Props = {
  city: City;
  snap: Snapshot;
  selectedCourier?: string | null;
  selectedOrder?: string | null;
  onCourier?: (id: string) => void;
  onOrder?: (id: string) => void;
  /** When set, a click on the map reports its coordinates instead of selecting things. */
  onPick?: (p: Pt) => void;
  picked?: Pt | null;
  /** Zoom to this courier and their route (the phone view). */
  focus?: string;
};

const ROAD_WIDTH: Record<string, number> = { avenue: 20, street: 11, old_town: 8, bridge: 16, path: 5 };

export function MapView({ city, snap, selectedCourier, selectedOrder, onCourier, onOrder, onPick, picked, focus }: Props) {
  const svg = useRef<SVGSVGElement>(null);
  const order = snap.orders.find((o) => o.id === selectedOrder);
  const highlight = focus ?? selectedCourier ?? order?.courierId ?? null;

  const viewBox = useMemo(() => {
    const c = focus && snap.couriers.find((c) => c.id === focus);
    if (!c) return `0 0 ${city.width} ${city.height}`;
    const pts = [[c.x, c.y] as Pt, ...c.route, ...c.stops.map((s) => [s.x, s.y] as Pt)];
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    const pad = 260;
    let x1 = Math.min(...xs) - pad, x2 = Math.max(...xs) + pad, y1 = Math.min(...ys) - pad, y2 = Math.max(...ys) + pad;
    // Keep a sensible minimum size and the screen's shape.
    const w = Math.max(x2 - x1, 1400), h = Math.max(y2 - y1, 1000);
    const cx = (x1 + x2) / 2, cy = (y1 + y2) / 2;
    x1 = cx - w / 2; y1 = cy - h / 2;
    return `${Math.round(x1)} ${Math.round(y1)} ${Math.round(w)} ${Math.round(h)}`;
    // Only re-zoom when the plan changes, not on every step, so the map doesn't swim.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [city, focus, snap.couriers.find((c) => c.id === focus)?.stops.map((s) => s.orderId + s.kind).join()]);

  const click = (e: MouseEvent<SVGSVGElement>) => {
    if (!onPick || !svg.current) return;
    const pt = svg.current.createSVGPoint();
    pt.x = e.clientX;
    pt.y = e.clientY;
    const p = pt.matrixTransform(svg.current.getScreenCTM()!.inverse());
    onPick([Math.round(p.x), Math.round(p.y)]);
  };

  return (
    <svg ref={svg} className={`map ${onPick ? "picking" : ""}`} viewBox={viewBox} preserveAspectRatio="xMidYMid meet" onClick={click} role="img" aria-label="Map of Valdera with couriers">
      <Base city={city} />

      {/* Every courier's road ahead, faint; the selected one bold. */}
      {snap.couriers.map((c) => (
        <polyline key={c.id} className="route" points={line(c.route)}
          style={{ stroke: `hsl(${hue(c.id)} 55% 45%)`, opacity: highlight ? (c.id === highlight ? 0.9 : 0.12) : 0.35, strokeWidth: c.id === highlight ? 16 : 9 }} />
      ))}

      {order && order.status !== "delivered" && (
        <line className="order-line" x1={order.pickup[0]} y1={order.pickup[1]} x2={order.dropoff[0]} y2={order.dropoff[1]} />
      )}

      {/* Doors waiting for a parcel. */}
      {snap.orders.filter((o) => o.status !== "delivered").map((o) => (
        <Door key={o.id} o={o} dim={!!highlight && o.courierId !== highlight && o.id !== selectedOrder} selected={o.id === selectedOrder} onClick={onOrder} />
      ))}

      <Shops city={city} waiting={snap.orders.filter((o) => o.status === "waiting").map((o) => o.merchantId)} />

      {snap.couriers.map((c) => (
        <CourierDot key={c.id} c={c} selected={c.id === highlight} dim={!!highlight && c.id !== highlight} onClick={onCourier} />
      ))}

      {picked && (
        <g transform={`translate(${picked[0]} ${picked[1]})`} className="picked">
          <circle r="60" />
          <circle r="16" />
        </g>
      )}
    </svg>
  );
}

const line = (pts: Pt[]) => pts.map((p) => `${p[0]},${p[1]}`).join(" ");

/** The parts of the map that never change, drawn once. */
const Base = memo(function Base({ city }: { city: City }) {
  const roads = useMemo(() => {
    const byKind: Record<string, string> = {};
    for (const r of city.roads) {
      const a = city.nodes[r.a], b = city.nodes[r.b];
      byKind[r.kind] = (byKind[r.kind] ?? "") + `M${a[0]} ${a[1]}L${b[0]} ${b[1]}`;
    }
    return byKind;
  }, [city]);
  const area = (k: string) => city.areas.find((a) => a.kind === k)?.outline ?? [];
  const order = ["path", "old_town", "street", "bridge", "avenue"];
  return (
    <g>
      <rect width={city.width} height={city.height} className="land" rx="40" />
      <polygon points={line(area("park"))} className="park" />
      <polygon points={line(area("oldtown"))} className="oldtown" />
      <polygon points={line(area("river"))} className="river" />
      {order.map((k) => roads[k] && <path key={`c${k}`} d={roads[k]} className={`casing r-${k}`} style={{ strokeWidth: ROAD_WIDTH[k] + 5 }} />)}
      {order.map((k) => roads[k] && <path key={k} d={roads[k]} className={`road r-${k}`} style={{ strokeWidth: ROAD_WIDTH[k] }} />)}
      <text x="1180" y="1565" className="area-label water">Río Valdera</text>
      <text x="870" y="690" className="area-label green" textAnchor="middle">Parque del Prado</text>
      <text x="2650" y="420" className="area-label old" textAnchor="middle">Casco Viejo</text>
      <g transform={`translate(${city.depot[0]} ${city.depot[1]})`} className="depot">
        <title>Depot</title>
        <rect x="-44" y="-44" width="88" height="88" rx="22" />
        <path d="M-22 4 L0 -18 L22 4 V22 H-22 Z" />
      </g>
    </g>
  );
});

const Shops = memo(function Shops({ city, waiting }: { city: City; waiting: string[] }) {
  return (
    <g>
      {city.merchants.map((m) => {
        const n = waiting.filter((w) => w === m.id).length;
        return (
          <g key={m.id} transform={`translate(${m.x} ${m.y})`} className="shop">
            {n > 0 && <circle r="70" className="waiting-ring" />}
            <rect x="-30" y="-30" width="60" height="60" rx="14" />
            <text className="shop-name" y="-52" textAnchor="middle">{m.name}</text>
            {n > 0 && <text className="shop-count" y="12" textAnchor="middle">{n}</text>}
          </g>
        );
      })}
    </g>
  );
}, (a, b) => a.city === b.city && a.waiting.join() === b.waiting.join());

function Door({ o, dim, selected, onClick }: { o: Order; dim: boolean; selected: boolean; onClick?: (id: string) => void }) {
  return (
    <g transform={`translate(${o.dropoff[0]} ${o.dropoff[1]})`} className={`door ${o.late ? "late" : ""} ${selected ? "on" : ""}`}
      style={{ opacity: dim ? 0.25 : 1, ["--h" as string]: hue(o.courierId) }} onClick={(e) => (e.stopPropagation(), onClick?.(o.id))}>
      <circle r={selected ? 34 : 22} />
    </g>
  );
}

function CourierDot({ c, selected, dim, onClick }: { c: Courier; selected: boolean; dim: boolean; onClick?: (id: string) => void }) {
  return (
    <g className={`courier ${selected ? "on" : ""} ${c.status === "waiting_for_phone" ? "waiting" : ""}`}
      style={{ transform: `translate(${c.x}px, ${c.y}px)`, opacity: dim ? 0.45 : 1, ["--h" as string]: hue(c.id) }}
      onClick={(e) => (e.stopPropagation(), onClick?.(c.id))}>
      <title>{`${c.name} · ${c.vehicle} · ${c.load}/${c.capacity}`}</title>
      {selected && <circle r="88" className="halo" />}
      {c.vehicle === "van" ? <rect x="-50" y="-50" width="100" height="100" rx="26" /> : <circle r="50" />}
      <text y="17" textAnchor="middle">{c.name[0]}</text>
    </g>
  );
}
