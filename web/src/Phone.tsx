import { useState } from "react";
import { api, clock, hue, minutes, type City, type Me, type Snapshot } from "./api";
import { MapView } from "./MapView";

type Props = { city: City; snap: Snapshot; courierId: string | null; me: Me; onSignOut: () => void };

/** What a courier sees on their phone: the next stop, the way there, and one big button. */
export function Phone({ city, snap, courierId, me, onSignOut }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const c = snap.couriers.find((c) => c.id === courierId);

  if (!c) {
    return (
      <div className="phone pick-courier">
        <header className="phone-bar">
          <div className="brand"><span className="logo" aria-hidden="true" />Waypoint</div>
          {me.role === "dispatcher" ? <a className="ghost" href="#/">Board</a> : <button className="ghost" onClick={onSignOut}>Sign out</button>}
        </header>
        <h1>Who's riding?</h1>
        <p className="lede">Pick a courier to see their route and run their stops from this phone.</p>
        <ul className="pick-list">
          {snap.couriers.map((x) => (
            <li key={x.id}>
              <a href={`#/courier/${x.id}`} style={{ ["--h" as string]: hue(x.id) }}>
                <span className={`avatar ${x.vehicle}`}>{x.name[0]}</span>
                <span>{x.name}<small>{x.vehicle} · {x.stops.length} stops</small></span>
              </a>
            </li>
          ))}
        </ul>
      </div>
    );
  }

  const stop = c.stops[0];
  const order = stop && snap.orders.find((o) => o.id === stop.orderId);
  const here = c.status === "waiting_for_phone" || c.status === "at_stop";
  const act = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="phone" style={{ ["--h" as string]: hue(c.id) }}>
      <header className="phone-bar">
        <a href="#/courier" className="back" aria-label="Pick another courier">‹</a>
        <span className={`avatar ${c.vehicle}`}>{c.name[0]}</span>
        <span className="who">{c.name}<small>{c.vehicle} · {c.load}/{c.capacity} on board</small></span>
        <span className="time">{clock(snap.clock)}</span>
      </header>

      <div className="phone-map">
        <MapView city={city} snap={snap} focus={c.id} />
      </div>

      <section className="sheet">
        <label className="switch">
          <input type="checkbox" checked={c.phone} disabled={busy} onChange={(e) => act(() => api.phone(c.id, e.target.checked))} />
          <span className="track" aria-hidden="true" />
          {c.phone ? "You're riding: stops wait for your tap" : "Simulated: stops complete by themselves"}
        </label>

        {!stop || !order ? (
          <div className="next none">
            <h2>No stops right now</h2>
            <p>Stay near the depot. New orders show up here the moment dispatch gives them to you.</p>
          </div>
        ) : (
          <div className={`next ${stop.kind}`}>
            <span className="kind">{stop.kind === "pickup" ? "Collect" : "Deliver"} · {order.id}{order.size > 1 && " · large"}</span>
            <h2>{stop.kind === "pickup" ? order.merchant : order.address}</h2>
            <p>
              {stop.kind === "pickup" ? `For ${order.customer}, ${order.address}` : `For ${order.customer}, from ${order.merchant}`}
            </p>
            <p className={`due ${order.late ? "late" : ""}`}>
              Due {clock(order.promisedBy)}
              {here ? " · you're there" : ` · there in ${minutes(Math.max(0, stop.arrival - snap.clock))}`}
            </p>
            <button className="big-action" disabled={!here || busy} onClick={() => act(() => api.complete(c.id))}>
              {!here ? (stop.kind === "pickup" ? "On the way to the shop…" : "On the way to the door…") : stop.kind === "pickup" ? "Picked up" : "Delivered"}
            </button>
            {error && <p className="error">{error}</p>}
          </div>
        )}

        {c.stops.length > 1 && (
          <ol className="later">
            {c.stops.slice(1).map((s) => (
              <li key={`${s.orderId}-${s.kind}`} className={s.kind}>
                <span className="dot" />
                {s.kind === "pickup" ? "Collect" : "Deliver"} {s.orderId}
                <small>{s.label}</small>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
