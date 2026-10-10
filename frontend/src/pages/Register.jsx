import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { extractErrorMessage } from '../api/client';

export default function Register() {
  const { register } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: '', email: '', phone: '', password: '', gender: '', age: '' });
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const validate = () => {
    const next = {};
    if (!form.fullName.trim()) next.fullName = 'Full name is required';
    if (!/^\S+@\S+\.\S+$/.test(form.email)) next.email = 'Enter a valid email address';
    if (!/^\d{10}$/.test(form.phone)) next.phone = 'Phone number must be 10 digits';
    if (form.password.length < 6) next.password = 'Password must be at least 6 characters';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!validate()) return;
    setLoading(true);
    try {
      const payload = { ...form, age: form.age ? Number(form.age) : null };
      const user = await register(payload);
      showToast(`Welcome to YatraRail, ${user.fullName.split(' ')[0]}!`, 'success');
      navigate('/dashboard', { replace: true });
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="container page" style={{ maxWidth: 480 }}>
      <div className="card">
        <h1>Create your account</h1>
        <p>Already registered? <Link to="/login" className="text-orange">Log in</Link></p>
        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="full-name">Full name</label>
            <input id="full-name" value={form.fullName} onChange={update('fullName')} placeholder="Ananya Sharma" />
            {errors.fullName && <div className="field-error">{errors.fullName}</div>}
          </div>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" type="email" value={form.email} onChange={update('email')} placeholder="you@example.com" />
            {errors.email && <div className="field-error">{errors.email}</div>}
          </div>
          <div className="field">
            <label htmlFor="phone-number">Phone number</label>
            <input id="phone-number" value={form.phone} onChange={update('phone')} placeholder="9876543210" maxLength={10} />
            {errors.phone && <div className="field-error">{errors.phone}</div>}
          </div>
          <div className="grid grid-2">
            <div className="field">
              <label htmlFor="gender">Gender</label>
              <select id="gender" value={form.gender} onChange={update('gender')}>
                <option value="">Select</option>
                <option>Female</option>
                <option>Male</option>
                <option>Other</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="age">Age</label>
              <input id="age" type="number" min="1" max="120" value={form.age} onChange={update('age')} />
            </div>
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" value={form.password} onChange={update('password')} placeholder="At least 6 characters" />
            {errors.password && <div className="field-error">{errors.password}</div>}
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={loading}>{loading ? 'Creating account...' : 'Create Account'}</button>
        </form>
      </div>
    </div>
  );
}
