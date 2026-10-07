import { useEffect, useRef, useState } from 'react';
import api from '../../api/client.js';

/**
 * Autosugerencias de "quien retira" / titulares: empleados ACTIVOS y personas
 * externas registradas. Debounce de 300 ms; el contador de secuencia descarta
 * respuestas que llegan después de una búsqueda más nueva.
 */
export default function useCandidateSearch(term, enabled = true) {
  const [suggestions, setSuggestions] = useState([]);
  const [show, setShow] = useState(false);
  const seqRef = useRef(0);

  useEffect(() => {
    const q = (term || '').trim();
    if (!enabled || q.length < 2) {
      seqRef.current++;
      setSuggestions([]);
      return undefined;
    }
    const t = setTimeout(() => {
      const seq = ++seqRef.current;
      api.get('/manual-consumptions/proxy-candidates', { params: { term: q } })
        .then((r) => {
          if (seq !== seqRef.current) return;
          setSuggestions(r.data || []);
          setShow(true);
        })
        .catch(() => { if (seq === seqRef.current) setSuggestions([]); });
    }, 300);
    return () => clearTimeout(t);
  }, [term, enabled]);

  function clear() {
    seqRef.current++;
    setSuggestions([]);
    setShow(false);
  }

  return { suggestions, show, setShow, clear };
}
