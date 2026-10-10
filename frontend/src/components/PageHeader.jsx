import Icon from './Icon';

/**
 * PageHeader — the one way a page announces itself.
 *
 * Every screen used to invent its own `<h1>`, spacing and action row, which is
 * why headings, filter bars and buttons landed at slightly different heights on
 * every page. This fixes the rhythm: an optional icon, a title, an optional
 * subtitle and an actions slot, all on the shared spacing scale.
 */
export default function PageHeader({ title, subtitle, icon, actions, children }) {
  return (
    <header className="page-header">
      <div className="page-header-main">
        {icon && (
          <span className="page-header-icon" aria-hidden="true">
            <Icon name={icon} size={22} />
          </span>
        )}
        <div className="page-header-text">
          <h1 className="page-header-title">{title}</h1>
          {subtitle && <p className="page-header-subtitle">{subtitle}</p>}
        </div>
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
      {children}
    </header>
  );
}
