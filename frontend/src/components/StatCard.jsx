import Icon from './Icon';

/**
 * StatCard — a single figure with a label, in the dashboard language.
 *
 * The dashboards each rendered their own tile inline (a div with a hand-picked
 * hex colour for the number). This is that idea once, with an icon chip, a
 * tone, an optional note and an optional trend. `tone` maps to a token-backed
 * colour, never a literal, so it is correct in both themes.
 *
 * tone: 'accent' | 'success' | 'warning' | 'danger' | 'info' | 'neutral'
 */
const TONE_ICON = {
  accent: 'var(--ink-accent)',
  success: 'var(--ink-success)',
  warning: 'var(--ink-warning)',
  danger: 'var(--ink-danger)',
  info: 'var(--ink-info)',
  neutral: 'var(--text-muted)',
};

export default function StatCard({
  label,
  value,
  icon,
  tone = 'neutral',
  note,
  trend,
  valueClass = '',
}) {
  return (
    <div className={`stat-tile tone-${tone}`}>
      <div className="stat-tile-head">
        <span className="stat-label">{label}</span>
        {icon && (
          <span className="stat-tile-icon" style={{ color: TONE_ICON[tone] }}>
            <Icon name={icon} size={18} />
          </span>
        )}
      </div>
      <div className={`stat-value ${valueClass}`}>{value}</div>
      {(note || trend) && (
        <div className="stat-tile-foot">
          {trend && (
            <span className="stat-trend">
              <Icon name="trending-up" size={14} />
              {trend}
            </span>
          )}
          {note && <span className="stat-note">{note}</span>}
        </div>
      )}
    </div>
  );
}
