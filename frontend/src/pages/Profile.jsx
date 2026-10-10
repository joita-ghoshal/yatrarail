import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { useToast } from '../context/ToastContext';
import { AuthApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import NotificationPreferences from '../components/NotificationPreferences';
import PageHeader from '../components/PageHeader';
import Icon from '../components/Icon';

export default function Profile() {
  const { user, logout, updateLocalUser, isTemporary, endTemporarySession } = useAuth();
  const { theme, setTheme } = useTheme();
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [profileForm, setProfileForm] = useState({
    fullName: user?.fullName || '',
    phone: user?.phone || '',
    gender: user?.gender || '',
    age: user?.age ?? '',
  });
  const [savingProfile, setSavingProfile] = useState(false);

  /* The profile is fetched by the auth context after /me resolves, so the form
     starts empty and has to catch up. Keying on the user id avoids overwriting
     whatever the user is currently typing on unrelated re-renders. */
  useEffect(() => {
    setProfileForm({
      fullName: user?.fullName || '',
      phone: user?.phone || '',
      gender: user?.gender || '',
      age: user?.age ?? '',
    });
  }, [user?.id, user?.fullName, user?.phone, user?.gender, user?.age]);

  const [pwForm, setPwForm] = useState({ currentPassword: '', newPassword: '' });
  const [savingPw, setSavingPw] = useState(false);

  const saveProfile = async (e) => {
    e.preventDefault();
    setSavingProfile(true);
    try {
      await AuthApi.updateProfile(profileForm);
      updateLocalUser({
        fullName: profileForm.fullName,
        phone: profileForm.phone,
        gender: profileForm.gender,
        age: profileForm.age === '' ? null : Number(profileForm.age),
      });
      showToast('Profile updated', 'success');
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSavingProfile(false);
    }
  };

  const changePassword = async (e) => {
    e.preventDefault();
    setSavingPw(true);
    try {
      await AuthApi.changePassword(pwForm);
      setPwForm({ currentPassword: '', newPassword: '' });
      showToast('Password changed successfully', 'success');
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSavingPw(false);
    }
  };

  /* Logging out must land on the public landing page, not on /login. This page
     is itself a protected route, so without an explicit navigation the auth
     guard would bounce the now-unauthenticated user straight to Login, and a
     later browser restore would reopen the app there. `replace` keeps the
     protected route out of the history stack so Back cannot return to it. */
  const handleLogout = async () => {
    if (isTemporary) {
      await endTemporarySession();
    } else {
      logout();
    }
    navigate('/', { replace: true });
  };

  return (
    <div className="container page" style={{ maxWidth: 640 }}>
      <PageHeader icon="user" title={isTemporary ? 'Your guest session' : 'Profile & Settings'} />

      {isTemporary ? (
        <GuestConversionCard />
      ) : (
        <>
          <div className="card" style={{ marginBottom: 20 }}>
            <h3 className="section-title">Account details</h3>
            <p className="text-muted">{user?.email} · {user?.role}</p>
            <form onSubmit={saveProfile}>
              <div className="grid grid-2">
                <div className="field">
                  <label htmlFor="full-name">Full name</label>
                  <input id="full-name" value={profileForm.fullName} onChange={(e) => setProfileForm((f) => ({ ...f, fullName: e.target.value }))} />
                </div>
                <div className="field">
                  <label htmlFor="phone">Phone</label>
                  <input id="phone" value={profileForm.phone} onChange={(e) => setProfileForm((f) => ({ ...f, phone: e.target.value }))} placeholder="Update phone number" />
                </div>
                <div className="field">
                  <label htmlFor="gender">Gender</label>
                  <select id="gender" value={profileForm.gender} onChange={(e) => setProfileForm((f) => ({ ...f, gender: e.target.value }))}>
                    <option value="">Unchanged</option>
                    <option>Female</option><option>Male</option><option>Other</option>
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="age">Age</label>
                  <input id="age" type="number" value={profileForm.age} onChange={(e) => setProfileForm((f) => ({ ...f, age: e.target.value }))} />
                </div>
              </div>
              <button type="submit" className="btn btn-primary" disabled={savingProfile}>{savingProfile ? 'Saving...' : 'Save Profile'}</button>
            </form>
          </div>

          <div className="card" style={{ marginBottom: 20 }}>
            <h3 className="section-title">Change password</h3>
            <form onSubmit={changePassword}>
              <div className="field">
                <label htmlFor="current-password">Current password</label>
                <input id="current-password" type="password" value={pwForm.currentPassword} onChange={(e) => setPwForm((f) => ({ ...f, currentPassword: e.target.value }))} />
              </div>
              <div className="field">
                <label htmlFor="new-password">New password</label>
                <input id="new-password" type="password" value={pwForm.newPassword} onChange={(e) => setPwForm((f) => ({ ...f, newPassword: e.target.value }))} />
              </div>
              <button type="submit" className="btn btn-primary" disabled={savingPw}>{savingPw ? 'Updating...' : 'Change Password'}</button>
            </form>
          </div>

          <NotificationPreferences />
        </>
      )}

      <div className="card" style={{ marginBottom: 20 }}>
        <h3 className="section-title">Appearance</h3>
        <div className="flex gap-sm">
          <button type="button" className={`btn ${theme === 'light' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTheme('light')}><Icon name="sun" size={18} /> Light</button>
          <button type="button" className={`btn ${theme === 'dark' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setTheme('dark')}><Icon name="moon" size={18} /> Dark</button>
        </div>
      </div>

      <button type="button" className="btn btn-danger" onClick={handleLogout}>
        {isTemporary ? 'End guest session' : 'Log out'}
      </button>
    </div>
  );
}

/**
 * A guest has no password and no editable profile - the backend refuses both for a temporary
 * session. The only way forward is to turn the session into a real account, which keeps every
 * booking already made under it.
 */
function GuestConversionCard() {
  const { user, convertToPermanent } = useAuth();
  const { showToast } = useToast();
  const [form, setForm] = useState({ email: '', password: '', confirmPassword: '' });
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    const next = {};
    if (!form.email.trim()) next.email = 'Email is required';
    if (form.password.length < 8) next.password = 'Use at least 8 characters';
    else if (!/[A-Za-z]/.test(form.password) || !/[0-9]/.test(form.password)) {
      next.password = 'Include at least one letter and one number';
    }
    if (form.password !== form.confirmPassword) next.confirmPassword = 'Passwords do not match';
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    setSaving(true);
    try {
      await convertToPermanent({ email: form.email.trim(), password: form.password });
      showToast('Your guest session is now a permanent account', 'success');
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="card" style={{ marginBottom: 20 }}>
      <h3 className="section-title">Keep your bookings</h3>
      <p className="field-hint" style={{ marginBottom: 12 }}>
        You are signed in as a guest, {user?.fullName}. Guest sessions close on their own and cannot
        hold a password, so create an account to keep your booking history. Everything you have
        already booked stays attached - nothing is lost.
      </p>
      <form onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" value={form.email} onChange={update('email')} placeholder="you@example.com" autoComplete="email" />
          {errors.email && <div className="field-error">{errors.email}</div>}
        </div>
        <div className="grid grid-2">
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" value={form.password} onChange={update('password')} placeholder="8+ characters" autoComplete="new-password" />
            {errors.password && <div className="field-error">{errors.password}</div>}
          </div>
          <div className="field">
            <label htmlFor="confirm-password">Confirm password</label>
            <input id="confirm-password" type="password" value={form.confirmPassword} onChange={update('confirmPassword')} autoComplete="new-password" />
            {errors.confirmPassword && <div className="field-error">{errors.confirmPassword}</div>}
          </div>
        </div>
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? 'Creating account...' : 'Create permanent account'}
        </button>
      </form>
    </div>
  );
}
