import { useState, useRef, useEffect, useMemo, useCallback } from 'react';

export default function EmployeeSelect({ employees, value, onChange }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [highlight, setHighlight] = useState(-1);
  const ref = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    function handleClickOutside(event) {
      if (ref.current && !ref.current.contains(event.target)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const selectedEmp = useMemo(() => employees.find(e => e.id === String(value) || e.id === Number(value)), [employees, value]);
  const displayValue = selectedEmp ? `${selectedEmp.identityCard} - ${selectedEmp.fullName}` : '';

  const filtered = useMemo(() => {
    if (!search) return employees;
    const term = search.toLowerCase();
    return employees.filter(e => 
      (e.fullName && e.fullName.toLowerCase().includes(term)) || 
      (e.identityCard && e.identityCard.includes(term))
    );
  }, [search, employees]);

  const select = useCallback((emp) => {
    onChange(emp ? emp.id : '');
    setOpen(false);
    setSearch('');
    setHighlight(-1);
  }, [onChange]);

  const handleKeyDown = useCallback((e) => {
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'Enter') {
        setOpen(true);
        setHighlight(0);
        e.preventDefault();
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown':
        setHighlight(i => Math.min(i + 1, filtered.length));
        e.preventDefault();
        break;
      case 'ArrowUp':
        setHighlight(i => Math.max(i - 1, -1));
        e.preventDefault();
        break;
      case 'Enter':
        if (highlight >= 0 && highlight < filtered.length) {
          select(filtered[highlight]);
        } else if (highlight === filtered.length) {
          select(null);
        }
        e.preventDefault();
        break;
      case 'Escape':
        setOpen(false);
        setHighlight(-1);
        inputRef.current?.blur();
        e.preventDefault();
        break;
    }
  }, [open, filtered, highlight, select]);

  const itemStyle = (idx) => ({
    padding: '10px 12px',
    cursor: 'pointer',
    borderBottom: '1px solid var(--border)',
    display: 'flex',
    flexDirection: 'column',
    background: highlight === idx ? 'var(--panel-2)' : 'transparent',
  });

  return (
    <div className="searchable-select" ref={ref} style={{ position: 'relative', width: '100%', minWidth: '220px' }}>
      <input
        ref={inputRef}
        type="text"
        placeholder="Todos (Buscar...)"
        value={open ? search : (value ? displayValue : '')}
        onClick={() => { setOpen(true); setHighlight(0); }}
        onFocus={() => { setOpen(true); }}
        onChange={(e) => {
          setSearch(e.target.value);
          setOpen(true);
          setHighlight(0);
        }}
        onKeyDown={handleKeyDown}
        style={{ width: '100%', boxSizing: 'border-box' }}
        autoComplete="off"
      />
      
      {open && (
        <div style={{
          position: 'absolute',
          top: '100%',
          left: 0,
          right: 0,
          maxHeight: '240px',
          overflowY: 'auto',
          background: 'var(--panel)',
          border: '1px solid var(--border)',
          borderRadius: '8px',
          marginTop: '4px',
          zIndex: 9999,
          boxShadow: '0 4px 12px rgba(0,0,0,0.5)'
        }}>
          <div
            style={{ ...itemStyle(filtered.length), borderBottom: '1px solid var(--border)' }}
            onClick={() => select(null)}
            onMouseEnter={() => setHighlight(filtered.length)}
          >
            Todos
          </div>
          {filtered.map((emp, idx) => (
            <div
              key={emp.id}
              style={itemStyle(idx)}
              onClick={() => select(emp)}
              onMouseEnter={() => setHighlight(idx)}
            >
              <span>{emp.fullName}</span>
              <span style={{ fontSize: '12px', color: 'var(--muted)' }}>{emp.identityCard}</span>
            </div>
          ))}
          {filtered.length === 0 && (
            <div style={{ padding: '10px 12px', color: 'var(--muted)', textAlign: 'center' }}>
              No hay resultados
            </div>
          )}
        </div>
      )}
    </div>
  );
}
