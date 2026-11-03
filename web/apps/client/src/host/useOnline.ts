import { useEffect, useState } from "react";

/** `navigator.onLine` El valor en tiempo real (el navegador tiene la última palabra). */
export function useOnline(): boolean {
  const [online, setOnline] = useState(readOnline);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const update = () => setOnline(readOnline());
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    update();
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);

  return online;
}

function readOnline(): boolean {
  if (typeof navigator === "undefined") return true;
  return navigator.onLine !== false;
}
