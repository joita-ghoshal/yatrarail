import { useState } from 'react';
import { Link } from 'react-router-dom';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = (e) => {
    e.preventDefault();
    setSubmitted(true);
  };

  return (
    <div className="container page" style={{ maxWidth: 460 }}>
      <div className="card">
        <h1>Reset your password</h1>
        {!submitted ? (
          <>
            <p>
              This academic demo does not send real password-reset emails. Once you submit,
              we'll show a confirmation screen; to actually change a password, log in and use
              the Change Password option in Settings, or ask an administrator to reset your demo account.
            </p>
            <form onSubmit={handleSubmit}>
              <div className="field">
                <label htmlFor="email">Email</label>
                <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" required />
              </div>
              <button type="submit" className="btn btn-primary btn-block">Send reset instructions</button>
            </form>
          </>
        ) : (
          <div>
            <p>If an account exists for <strong>{email}</strong>, instructions would be sent in a production system.</p>
            <p className="field-hint">Demo limitation: no email is actually sent. Please use <Link to="/login">Log in</Link> with a demo account, or Settings → Change Password once signed in.</p>
          </div>
        )}
      </div>
    </div>
  );
}
