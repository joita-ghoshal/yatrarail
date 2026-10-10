import { useEffect, useState } from 'react';
import { Link, useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { extractErrorMessage } from '../api/client';
import GuestSessionPanel from '../components/GuestSessionPanel';

export default function Login() {
  const { login } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const [form, setForm] = useState({ email: '', password: '' });
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);

  /* "Continue as Guest" in the header links here with ?guest=1 so a visitor can
     reach the guest flow from the landing page. The panel reveals itself and is
     scrolled into view; the flow itself is untouched. */
  const guestIntent = searchParams.get('guest') === '1';
  useEffect(() => {
    if (guestIntent) document.getElementById('guest-session')?.scrollIntoView({ block: 'start' });
  }, [guestIntent]);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const validate = () => {
    const next = {};
    if (!form.email) next.email = 'Email is required';
    if (!form.password) next.password = 'Password is required';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!validate()) return;
    setLoading(true);
    try {
      const user = await login(form);
      showToast(`Welcome back, ${user.fullName.split(' ')[0]}!`, 'success');
      const dest = location.state?.from || (user.role === 'ADMIN' ? '/admin' : user.role === 'STAFF' ? '/staff' : '/dashboard');
      navigate(dest, { replace: true });
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="container page" style={{ maxWidth: 460 }}>
      <div className="card">
        <h1>Log in to YatraRail</h1>
        <p>New here? <Link to="/register" className="text-orange">Create an account</Link></p>
        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" type="email" value={form.email} onChange={update('email')} placeholder="you@example.com" />
            {errors.email && <div className="field-error">{errors.email}</div>}
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" value={form.password} onChange={update('password')} placeholder="••••••••" />
            {errors.password && <div className="field-error">{errors.password}</div>}
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={loading}>{loading ? 'Logging in...' : 'Log In'}</button>
        </form>
        <p className="field-hint" style={{ marginTop: 16 }}>
          Demo accounts: admin@yatrarail.demo / Admin@123 · staff@yatrarail.demo / Staff@123 · passenger@yatrarail.demo / Passenger@123
        </p>
        <Link to="/forgot-password" className="field-hint">Forgot your password?</Link>
      </div>

      <GuestSessionPanel
        defaultOpen={guestIntent}
        onStarted={(user) => {
          showToast(`Guest session started, ${user.fullName.split(' ')[0]}`, 'success');
          navigate(location.state?.from || '/search', { replace: true });
        }}
      />
    </div>
  );
}
