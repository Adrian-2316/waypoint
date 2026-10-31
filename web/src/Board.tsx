import { useEffect, useState } from "react";
import { api, clock, hue, minutes, type City, type Courier, type Me, type Order, type Pt, type Report, type Snapshot } from "./api";
import { MapView } from "./MapView";

type Props = { city: City; snap: Snapshot; me: Me; onSignOut: () => void };

const STATUS: Record<Order["status"], string> = { waiting: "Waiting", assigned: "On the way to shop", picked_up: "On board", delivered: "Delivered" };

export function Board({ city, snap, onSignOut }: Props) {
  const [courierId, setCourierId] = useState<string | null>(null);
  const [orderId, setOrderId] = useState<string | null>(null);
  const [tab, setTab] = useState<"active" | "done">("active");
  const [creating, setCreating] = useState(false);
  const [picked, setPicked] = useState<Pt | null>(null);
  const [toast, setToast] = useState<{ ok: boolean; text: string } | null>(null);
  const [report, setReport] = useState<Report>([]);

  useEffect(() => {
    const load = () => api.report().then(setReport).catch(() => {});
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 4000);
    return () => clearTimeout(t);
  }, [toast]);

  const run = async (work: () => Promise<string | void>) => {
    try {
      const text = await work();
      if (text) setToast({ ok: true, text });
    } catch (e) {
      setToast({ ok: false, text: (e as Error).message });
    }
  };

  const orders = snap.orders.filter((o) => (tab === "active" ? o.status !== "delivered" : o.status === "delivered"));
  const selectedCourier = snap.couriers.find((c) => c.id === courierId) ?? null;
  const name = (id: string | null) => snap.couriers.find((c) => c.id === id)?.name ?? "—";

  return (
    <div className="board">
      <header className="bar">
        <div className="brand">
          <span className="logo" aria-hidden="true" />
          Waypoint
        </div>
        <div className="clock" title="Simulated time in Valdera">
          {clock(snap.clock)}
          {snap.paused && <span className="paused">paused</span>}
        </div>
        <div className="controls">
          <Segmented label="Speed" value={snap.paused ? "pause" : String(snap.speed)}
            options={[["pause", "❚❚"], ["1", "1×"], ["10", "10×"], ["30", "30×"]]}
            onChange={(v) => run(() => api.settings(v === "pause" ? { paused: true } : { paused: false, speed: Number(v) }))} />
          <Segmented label="Demand" value={snap.demand} options={[["calm", "Calm"], ["steady", "Steady"], ["rush", "Rush"]]}
            onChange={(v) => run(() => api.settings({ demand: v }))} />
          <label className="switch">
            <input type="checkbox" checked={snap.autoDispatch} onChange={(e) => run(() => api.settings({ autoDispatch: e.target.checked }))} />
            <span className="track" aria-hidden="true" />
            Auto-dispatch
          </label>
        </div>
        <div className="me">
          <a href="#/courier" className="ghost">Phone view</a>
          <button className="ghost" onClick={onSignOut}>Sign out</button>
        </div>
      </header>

      <aside className="left">
        <div className="kpis">
          <Kpi label="On time" value={`${snap.kpis.onTimePercent}%`} tone={snap.kpis.onTimePercent >= 90 ? "good" : "warn"} />
          <Kpi label="Delivered" value={snap.kpis.delivered} />
          <Kpi label="Avg. delivery" value={`${snap.kpis.averageMinutes}′`} />
          <Kpi label="Waiting" value={snap.kpis.waiting} tone={snap.kpis.waiting > 0 ? "warn" : undefined} />
        </div>

        <div className="panel-head">
          <div className="tabs" role="tablist">
            <button role="tab" aria-selected={tab === "active"} className={tab === "active" ? "on" : ""} onClick={() => setTab("active")}>
              Active <span>{snap.kpis.active}</span>
            </button>
            <button role="tab" aria-selected={tab === "done"} className={tab === "done" ? "on" : ""} onClick={() => setTab("done")}>
              Delivered
            </button>
          </div>
          <button className="small primary" onClick={() => { setCreating(!creating); setPicked(null); }}>
            {creating ? "Cancel" : "+ New order"}
          </button>
        </div>

        {creating && (
          <NewOrder city={city} picked={picked} onDone={(id) => { setCreating(false); setPicked(null); setOrderId(id); setTab("active"); }} run={run} />
        )}

        <ul className="orders">
          {orders.length === 0 && <li className="empty">{tab === "active" ? "No orders right now." : "Nothing delivered in the last 20 minutes."}</li>}
          {orders.map((o) => (
            <li key={o.id}>
              <button className={`order ${o.id === orderId ? "on" : ""} ${o.late ? "late" : ""}`} onClick={() => { setOrderId(o.id === orderId ? null : o.id); setCourierId(null); }}>
                <div className="o-top">
                  <span className="o-id">{o.id}</span>
                  <span className={`chip s-${o.status}`}>{STATUS[o.status]}</span>
                  <span className="o-due">{o.status === "delivered" ? clock(o.deliveredAt!) : `due ${clock(o.promisedBy)}`}</span>
                </div>
                <div className="o-route">
                  <b>{o.merchant}</b> <span className="arrow">→</span> {o.address}
                </div>
                <div className="o-foot">
                  {o.courierId ? (
                    <span className="who"><i style={{ ["--h" as string]: hue(o.courierId) }} />{name(o.courierId)}{o.pinned && " · by hand"}</span>
                  ) : (
                    <span className="who none">Nobody yet</span>
                  )}
                  {o.eta != null && o.status !== "delivered" && (
                    <span className={o.late ? "eta late" : "eta"}>ETA {clock(o.eta)}{o.late && ` · ${minutes(o.eta - o.promisedBy)} late`}</span>
                  )}
                  {o.status === "delivered" && <span className={o.late ? "eta late" : "eta good"}>{o.late ? "Late" : "On time"}</span>}
                </div>
              </button>
              {o.id === orderId && (o.status === "waiting" || o.status === "assigned") && (
                <Assign order={o} couriers={snap.couriers} run={run} />
              )}
            </li>
          ))}
        </ul>
      </aside>

      <main className="stage">
        <MapView city={city} snap={snap} selectedCourier={courierId} selectedOrder={orderId}
          onCourier={(id) => { setCourierId(id === courierId ? null : id); setOrderId(null); }}
          onOrder={(id) => { setOrderId(id); setCourierId(null); setTab("active"); }}
          onPick={creating ? setPicked : undefined} picked={creating ? picked : null} />
        {creating && <div className="hint">Click the map where the parcel should go.</div>}
        <div className="legend">
          <span><i className="lg-bike" /> Bike</span>
          <span><i className="lg-van" /> Van</span>
          <span><i className="lg-shop" /> Shop</span>
          <span><i className="lg-door" /> Drop-off</span>
          <span><i className="lg-wait" /> Order waiting</span>
        </div>
        {toast && <div className={`toast ${toast.ok ? "" : "bad"}`} role="status">{toast.text}</div>}
      </main>

      <aside className="right">
        <h3>Couriers <span>{snap.kpis.busyCouriers} of {snap.couriers.length} busy</span></h3>
        <ul className="couriers">
          {snap.couriers.map((c) => (
            <li key={c.id}>
              <CourierRow c={c} on={c.id === courierId} stats={report.find((r) => r.courierId === c.id)}
                onClick={() => { setCourierId(c.id === courierId ? null : c.id); setOrderId(null); }} />
            </li>
          ))}
        </ul>
        {selectedCourier && <Plan c={selectedCourier} orders={snap.orders} />}
        {!selectedCourier && (
          <>
            <h3>Activity</h3>
            <ul className="feed">
              {snap.events.slice(0, 14).map((e, i) => (
                <li key={`${e.at}-${i}`} className={`ev-${e.kind}`}>
                  <time>{clock(e.at)}</time>
                  <span>{e.text}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </aside>
    </div>
  );
}

function Kpi({ label, value, tone }: { label: string; value: string | number; tone?: "good" | "warn" }) {
  return (
    <div className={`kpi ${tone ?? ""}`}>
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}

function Segmented({ label, value, options, onChange }: { label: string; value: string; options: [string, string][]; onChange: (v: string) => void }) {
  return (
    <div className="seg" role="radiogroup" aria-label={label}>
      {options.map(([v, text]) => (
        <button key={v} role="radio" aria-checked={value === v} className={value === v ? "on" : ""} onClick={() => onChange(v)} title={`${label}: ${v}`}>
          {text}
        </button>
      ))}
    </div>
  );
}

function CourierRow({ c, on, stats, onClick }: { c: Courier; on: boolean; stats?: Report[number]; onClick: () => void }) {
  const next = c.stops[0];
  const state =
    c.status === "waiting_for_phone" ? "Waiting for a tap" :
    c.status === "at_stop" ? (next?.kind === "pickup" ? "Collecting" : "Handing over") :
    next ? (next.kind === "pickup" ? `To ${next.label}` : `Delivering to ${next.label}`) : "Free";
  return (
    <button className={`courier-row ${on ? "on" : ""}`} onClick={onClick} style={{ ["--h" as string]: hue(c.id) }}>
      <span className={`avatar ${c.vehicle}`}>{c.name[0]}</span>
      <span className="c-main">
        <span className="c-name">
          {c.name}
          <small>{c.vehicle}</small>
          {c.phone && <small className="ph">phone</small>}
        </span>
        <span className={`c-state ${next ? "" : "free"}`}>{state}</span>
      </span>
      <span className="c-side">
        <span className="load" title={`${c.load} of ${c.capacity} units on board`}>
          {Array.from({ length: c.capacity }, (_, i) => <i key={i} className={i < c.load ? "full" : ""} />)}
        </span>
        <small>{stats ? `${stats.delivered} done` : "0 done"}</small>
      </span>
    </button>
  );
}

function Plan({ c, orders }: { c: Courier; orders: Order[] }) {
  return (
    <div className="plan">
      <h3>{c.name}'s plan <a href={`#/courier/${c.id}`}>Phone view</a></h3>
      {c.stops.length === 0 && <p className="empty">No stops. {c.name} is free for the next order.</p>}
      <ol>
        {c.stops.map((s, i) => {
          const o = orders.find((o) => o.id === s.orderId);
          return (
            <li key={`${s.orderId}-${s.kind}`} className={s.kind}>
              <span className="dot" />
              <span>
                <b>{s.kind === "pickup" ? "Collect" : "Deliver"} {s.orderId}</b>
                <small>{s.label}{s.kind === "dropoff" && o?.eta != null && ` · ETA ${clock(o.eta)}`}{i === 0 && c.status === "at_stop" && " · now"}</small>
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function Assign({ order, couriers, run }: { order: Order; couriers: Courier[]; run: (w: () => Promise<string | void>) => void }) {
  const [to, setTo] = useState(couriers.find((c) => c.id !== order.courierId)?.id ?? "");
  return (
    <div className="assign">
      <label>
        Give to
        <select value={to} onChange={(e) => setTo(e.target.value)}>
          {couriers.filter((c) => c.id !== order.courierId).map((c) => (
            <option key={c.id} value={c.id}>{c.name} ({c.vehicle}, {c.stops.length} stops)</option>
          ))}
        </select>
      </label>
      <button className="small primary" onClick={() => run(async () => {
        const r = await api.assign(order.id, to);
        return `${order.id} is with ${couriers.find((c) => c.id === r.courierId)?.name} now (+${r.extraMinutes} min to their plan)`;
      })}>Assign</button>
    </div>
  );
}

function NewOrder({ city, picked, onDone, run }: { city: City; picked: Pt | null; onDone: (id: string) => void; run: (w: () => Promise<string | void>) => void }) {
  const [shop, setShop] = useState(city.merchants[0]?.id ?? "");
  const [customer, setCustomer] = useState("");
  const [size, setSize] = useState(1);
  return (
    <form className="new-order" onSubmit={(e) => {
      e.preventDefault();
      if (!picked) return;
      run(async () => {
        const r = await api.createOrder({ merchantId: shop, customer: customer.trim() || "Walk-in customer", x: picked[0], y: picked[1], size });
        onDone(r.id);
        return `${r.id} created`;
      });
    }}>
      <label>From
        <select value={shop} onChange={(e) => setShop(e.target.value)}>
          {city.merchants.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
        </select>
      </label>
      <label>Customer
        <input value={customer} maxLength={60} onChange={(e) => setCustomer(e.target.value)} placeholder="Name" />
      </label>
      <label>Size
        <select value={size} onChange={(e) => setSize(Number(e.target.value))}>
          <option value={1}>Small (1 unit)</option>
          <option value={2}>Large (2 units)</option>
        </select>
      </label>
      <p className={picked ? "pick ok" : "pick"}>{picked ? "Drop-off picked on the map." : "Now click the map to set the drop-off."}</p>
      <button className="primary" disabled={!picked}>Create order</button>
    </form>
  );
}
