import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { AuthApi, TempSessionApi } from '../api/endpoints';

const AuthContext = createContext(null);

function readStoredUser() {
  const raw = localStorage.getItem('yr_user');
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    localStorage.removeItem('yr_user');
    return null;
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(readStoredUser);
  const [sessionChecked, setSessionChecked] = useState(false);
  // Seconds left on a guest session, as reported by the server. The backend is the authority; this
  // is only what the UI counts down from, so a stale tab can never extend a dead session.
  const [sessionSecondsLeft, setSessionSecondsLeft] = useState(null);

  const clearSession = useCallback(() => {
    localStorage.removeItem('yr_token');
    localStorage.removeItem('yr_user');
    setUser(null);
    setSessionSecondsLeft(null);
    setSessionChecked(true);
  }, []);

  useEffect(() => {
    const token = localStorage.getItem('yr_token');
    if (!token || !user) {
      setSessionChecked(true);
      return;
    }

    // Validate the stored session against the server on boot so an expired or
    // forged token is not trusted even if it still sits in localStorage.
    let cancelled = false;
    AuthApi.me()
      .then((res) => {
        if (cancelled) return;
        const data = res.data?.data;
        if (!data) return;
        const next = {
          id: data.id,
          fullName: data.fullName,
          email: data.email,
          role: data.role,
          phone: data.phone,
          gender: data.gender,
          age: data.age,
        };
        if (data.temporaryAccount) {
          next.temporaryAccount = true;
          next.sessionExpiresAt = data.sessionExpiresAt;
        }
        localStorage.setItem('yr_user', JSON.stringify(next));
        setUser(next);
        if (data.temporaryAccount) {
          TempSessionApi.session()
            .then((s) => {
              if (!cancelled) setSessionSecondsLeft(s.data?.data?.expiresInSeconds ?? null);
            })
            .catch(() => {
              if (!cancelled) clearSession();
            });
        }
      })
      .catch(() => {
        if (cancelled) return;
        clearSession();
      })
      .finally(() => {
        if (!cancelled) setSessionChecked(true);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Tick the guest countdown down locally, but re-check the server periodically: the session can
  // be ended from another tab or device, and only the server can tell us.
  useEffect(() => {
    if (!user?.temporaryAccount || sessionSecondsLeft === null) return undefined;
    if (sessionSecondsLeft <= 0) {
      clearSession();
      return undefined;
    }
    const timer = setInterval(() => setSessionSecondsLeft((s) => (s === null ? null : s - 1)), 1000);
    const revalidate = setInterval(() => {
      TempSessionApi.session()
        .then((res) => {
          const data = res.data?.data;
          if (!data) return;
          if (data.sessionStatus !== 'ACTIVE' || data.expiresInSeconds <= 0) {
            clearSession();
          } else {
            setSessionSecondsLeft(data.expiresInSeconds);
          }
        })
        .catch(() => clearSession());
    }, 30000);
    return () => {
      clearInterval(timer);
      clearInterval(revalidate);
    };
  }, [user?.temporaryAccount, sessionSecondsLeft === null, clearSession]);

  const persist = (authResponse, extra = {}) => {
    localStorage.setItem('yr_token', authResponse.token);
    const userData = {
      id: authResponse.userId,
      fullName: authResponse.fullName,
      email: authResponse.email,
      role: authResponse.role,
      ...extra,
    };
    localStorage.setItem('yr_user', JSON.stringify(userData));
    setUser(userData);
    setSessionChecked(true);
    return userData;
  };

  /**
   * The auth response carries only the token, id, name, email and role — it has no phone, gender or
   * age. Those are the fields the Profile page edits, so without this the form would be blank after
   * a fresh sign-in until a reload happened to trigger the boot-time /me call. /me is the
   * authoritative source, so the token is stored first and the profile is filled in behind it.
   */
  const hydrateProfile = (userId) => {
    AuthApi.me()
      .then((res) => {
        const data = res.data?.data;
        if (!data || data.id !== userId) return;
        setUser((prev) => {
          if (!prev || prev.id !== userId) return prev;
          const next = {
            ...prev,
            fullName: data.fullName ?? prev.fullName,
            email: data.email ?? prev.email,
            role: data.role ?? prev.role,
            phone: data.phone ?? null,
            gender: data.gender ?? null,
            age: data.age ?? null,
          };
          localStorage.setItem('yr_user', JSON.stringify(next));
          return next;
        });
      })
      .catch(() => {
        /* The session is valid enough to use; a failed profile read must not sign anyone out. */
      });
  };

  const login = async (credentials) => {
    const res = await AuthApi.login(credentials);
    const stored = persist(res.data.data);
    hydrateProfile(stored.id);
    return stored;
  };

  const register = async (payload) => {
    const res = await AuthApi.register(payload);
    const stored = persist(res.data.data);
    hydrateProfile(stored.id);
    return stored;
  };

  /** Opens a guest one-time session. The server decides how long it lasts. */
  const startTemporarySession = async (payload) => {
    const res = await TempSessionApi.create(payload);
    const data = res.data.data;
    setSessionSecondsLeft(data.session?.expiresInSeconds ?? null);
    return persist(
      {
        token: data.token,
        userId: data.session.userId,
        fullName: data.session.fullName,
        email: data.session.email,
        role: data.session.role,
      },
      { temporaryAccount: true, sessionExpiresAt: data.session.expiresAt },
    );
  };

  /** Ends the guest session on the server first, so the token dies even if this tab is closed. */
  const endTemporarySession = async () => {
    try {
      await TempSessionApi.end();
    } finally {
      clearSession();
    }
  };

  /** Promotes the guest to a permanent member. Bookings stay attached to the same account. */
  const convertToPermanent = async (payload) => {
    const res = await TempSessionApi.convert(payload);
    const data = res.data.data;
    setSessionSecondsLeft(null);
    return persist(
      {
        token: localStorage.getItem('yr_token'),
        userId: data.userId,
        fullName: data.fullName,
        email: data.email,
        role: data.role,
      },
      { temporaryAccount: false, sessionExpiresAt: null },
    );
  };

  const logout = () => {
    // Fire-and-forget: a standard member has nothing to revoke, and a guest's session is already
    // dead by the time this runs, so a failure here must never trap the user in the app.
    if (user?.temporaryAccount) {
      TempSessionApi.end().catch(() => {});
    }
    clearSession();
  };

  const updateLocalUser = (partial) => {
    setUser((prev) => {
      const next = { ...prev, ...partial };
      localStorage.setItem('yr_user', JSON.stringify(next));
      return next;
    });
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        login,
        register,
        logout,
        updateLocalUser,
        isAuthenticated: !!user,
        sessionChecked,
        isTemporary: !!user?.temporaryAccount,
        sessionSecondsLeft,
        startTemporarySession,
        endTemporarySession,
        convertToPermanent,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}