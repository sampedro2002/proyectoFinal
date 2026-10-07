import { useRef, useState } from 'react';

/**
 * Buscador de empleados con autosugerencias. DEBE estar a nivel de módulo (no dentro
 * de ManualScan): si se define dentro del componente, cada render crea una función
 * nueva → React lo trata como un tipo distinto, desmonta/remonta el <input> en cada
 * tecla y se pierde el foco tras escribir una sola letra.
 */
export default function EmployeePicker({ label, term, setTerm, suggestions, show, setShow, onPick, selected, selectedLabel, placeholder, onClear, disabled = false }) {
  const [highlight, setHighlight] = useState(-1);
  const inputRef = useRef(null);

  const handleKeyDown = (e) => {
    if (!show || suggestions.length === 0) {
      if (e.key === 'ArrowDown') {
        setShow(true);
        setHighlight(0);
        e.preventDefault();
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown':
        setHighlight(i => Math.min(i + 1, suggestions.length - 1));
        e.preventDefault();
        break;
      case 'ArrowUp':
        setHighlight(i => Math.max(i - 1, 0));
        e.preventDefault();
        break;
      case 'Enter':
        if (highlight >= 0 && highlight < suggestions.length) {
          onPick(suggestions[highlight]);
          setShow(false);
          setHighlight(-1);
        }
        e.preventDefault();
        break;
      case 'Escape':
        setShow(false);
        setHighlight(-1);
        inputRef.current?.blur();
        e.preventDefault();
        break;
    }
  };

  const handlePick = (emp) => {
    onPick(emp);
    setShow(false);
    setHighlight(-1);
  };

  return (
    <div className="field" style={{ position: 'relative' }}>
      <label>{label}</label>
      <input
        disabled={disabled}
        ref={inputRef}
        value={term}
        onChange={(e) => { setTerm(e.target.value); if (onPick) onPick(null); if (onClear) onClear(); setHighlight(0); }}
        onFocus={() => { if (suggestions.length) { setShow(true); setHighlight(0); } }}
        onBlur={() => setTimeout(() => { setShow(false); setHighlight(-1); }, 150)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        autoComplete="off"
      />
      {!disabled && show && suggestions.length > 0 && (
        <ul style={{
          position: 'absolute', top: '100%', left: 0, right: 0,
          background: 'var(--panel, #1e293b)', border: '1px solid var(--border, #334155)',
          borderRadius: 6, margin: 0, padding: 0, listStyle: 'none',
          zIndex: 50, maxHeight: 240, overflowY: 'auto',
        }}>
          {suggestions.map((emp, idx) => (
            <li
              key={emp.id}
              onMouseDown={() => handlePick(emp)}
              style={{
                padding: '8px 12px', cursor: 'pointer',
                borderBottom: '1px solid var(--border, #334155)',
                background: highlight === idx ? 'rgba(255,255,255,.06)' : 'transparent',
              }}
              onMouseEnter={() => setHighlight(idx)}
            >
              <div>{emp.fullName}</div>
              <div style={{ fontSize: 12, color: '#64748b' }}>
                {emp.identityCard}
                {emp.status !== 'ACTIVE' && ` · ${emp.status}`}
              </div>
            </li>
          ))}
        </ul>
      )}
      {selected && (
        <div style={{ fontSize: 12, color: 'var(--ok, #16a34a)', marginTop: 4 }}>
          {selectedLabel}
        </div>
      )}
    </div>
  );
}
