export default function Drawer({ isOpen, title, onClose, children, width = 480 }) {
  if (!isOpen) return null;

  const handleKeyDown = (e) => {
    if (e.key === 'Escape' && onClose) onClose();
  };

  return (
    <div
      className="drawer-overlay"
      onKeyDown={handleKeyDown}
      tabIndex={-1}
    >
      <div className="drawer-panel" style={{ width, maxWidth: '96%' }}>
        <div className="drawer-header">
          <h3 style={{ margin: 0 }}>{title}</h3>
          <button type="button" className="ghost" onClick={onClose}>Cerrar</button>
        </div>
        <div className="drawer-body">
          {children}
        </div>
      </div>
    </div>
  );
}
