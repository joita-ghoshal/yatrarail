import { Children, cloneElement, isValidElement, useId } from 'react';
import Icon from './Icon';

/**
 * A labelled form control.
 *
 * <Field> exists because the app had 105 <label> elements and only 4 of them
 * were associated with their control: a sibling <label> followed by an <input>
 * gives the input no accessible name at all, so a screen reader announced
 * nothing for that field. Centralising the association means it cannot drift:
 * the label gets an id, the control gets htmlFor, the hint and error get
 * aria-describedby, and an error also flips aria-invalid.
 *
 * The control is passed as the single child and is cloned to receive those
 * attributes. Any id the caller supplies on the control wins, so a form that
 * needs to wire up something else itself can still do so.
 */
export default function Field({
  label,
  hint,
  error,
  required = false,
  optional = false,
  htmlFor,
  className = '',
  children,
}) {
  const generatedId = useId();
  const child = Children.only(children);

  if (!isValidElement(child)) {
    // A Field without a control would render a label pointing at nothing, which
    // is worse than a plain div because it looks correct in review.
    // eslint-disable-next-line no-console
    console.error('<Field> expects a single form control as its child.');
    return child ?? null;
  }

  const controlId = child.props.id || htmlFor || generatedId;
  const hintId = hint ? `${controlId}-hint` : null;
  const errorId = error ? `${controlId}-error` : null;
  const describedBy = [child.props['aria-describedby'], hintId, errorId].filter(Boolean).join(' ');

  const control = cloneElement(child, {
    id: controlId,
    'aria-invalid': error ? 'true' : child.props['aria-invalid'],
    'aria-describedby': describedBy || undefined,
    'aria-required': required || child.props.required ? 'true' : undefined,
    required: required || undefined,
  });

  return (
    <div className={`field ${error ? 'has-error' : ''} ${className}`.trim()}>
      {label && (
        <label className="field-label" htmlFor={controlId}>
          {label}
          {required && <span aria-hidden="true" className="text-danger"> *</span>}
          {optional && <span className="text-muted text-xs"> (optional)</span>}
        </label>
      )}
      {control}
      {hint && <div className="field-hint" id={hintId}>{hint}</div>}
      {error && (
        <div className="field-error" id={errorId} role="alert">
          <Icon name="alert" size={14} />
          <span>{error}</span>
        </div>
      )}
    </div>
  );
}

/**
 * A check row whose whole area is the hit target. Used for checkbox and radio
 * groups, where the box itself is far below the 44px minimum target.
 */
export function CheckRow({ label, meta, ...inputProps }) {
  const id = useId();
  return (
    <label className="check-row" htmlFor={id}>
      <input id={id} {...inputProps} />
      <span className="check-body">
        <span className="check-title">{label}</span>
        {meta && <span className="check-meta">{meta}</span>}
      </span>
    </label>
  );
}

/**
 * A group of related checkboxes or radios. The fieldset/legend pairing is what
 * lets a screen reader announce the question ("Which passengers need help?")
 * before each option, instead of four anonymous checkboxes in a row.
 */
export function FieldSet({ legend, hint, className = '', children }) {
  return (
    <fieldset className={`field ${className}`.trim()}>
      <legend className="field-label">{legend}</legend>
      {hint && <div className="field-hint" style={{ marginTop: 0, marginBottom: 'var(--space-2)' }}>{hint}</div>}
      {children}
    </fieldset>
  );
}