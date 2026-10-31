import { useEffect, useState, type FormEvent } from "react";
import { api, live, Unauthorized, type City, type Me, type Snapshot } from "./api";
import { Board } from "./Board";
import { Phone } from "./Phone";

/** Hash routes: #/ is the board, #/courier and #/courier/<id> the phone view. */
function useRoute() {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const on = () => setHash(location.hash);
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  const parts = hash.replace(/^#\/?/, "").split("/");
  return { view: parts[0] === "courier" ? "courier" : "board", courierId: parts[1] || null };
}

export default function App() {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [city, setCity] = useState<City | null>(null);
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [connected, setConnected] = useState(true);
  const route = useRoute();

  useEffect(() => {
    api.me().then(setMe).catch((e) => setMe(e instanceof Unauthorized ? null : null));
  }, []);

  useEffect(() => {
    if (!me) return;
    api.city().then(setCity).catch(() => setMe(null));
    return live(setSnap, setConnected);
  }, [me]);

  const signOut = async () => {
    await api.logout().catch(() => {});
    setMe(null);
    setSnap(null);
  };

  if (me === undefined) return null;
  if (me === null) return <Login onIn={setMe} />;
  if (!city || !snap) return <div className="loading">Opening the live map…</div>;

  const courierView = route.view === "courier" || me.role === "courier";
  return (
    <>
      {!connected && <div className="offline">Live feed lost. Reconnecting…</div>}
      {courierView ? (
        <Phone city={city} snap={snap} courierId={route.courierId} me={me} onSignOut={signOut} />
      ) : (
        <Board city={city} snap={snap} me={me} onSignOut={signOut} />
      )}
    </>
  );
}

function Login({ onIn }: { onIn: (me: Me) => void }) {
  const [user, setUser] = useState("dispatcher");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.login(user, password);
      onIn(await api.me());
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <form onSubmit={submit} className="login-card">
        <div className="brand big">
          <span className="logo" aria-hidden="true" />
          Waypoint
        </div>
        <p className="lede">Live dispatch for same-day deliveries in Valdera.</p>
        <div className="who" role="radiogroup" aria-label="Sign in as">
          {(["dispatcher", "courier"] as const).map((u) => (
            <button type="button" key={u} role="radio" aria-checked={user === u} className={user === u ? "on" : ""} onClick={() => setUser(u)}>
              {u === "dispatcher" ? "Dispatcher" : "Courier"}
              <small>{u === "dispatcher" ? "The live board" : "The phone view"}</small>
            </button>
          ))}
        </div>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" autoFocus required />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="primary" disabled={busy || !password}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
