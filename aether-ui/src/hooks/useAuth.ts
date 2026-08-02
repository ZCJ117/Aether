import { useState, useEffect, useCallback } from 'react';
import client, { setTokens, clearTokens, getAccessToken } from '../api/client';

interface User {
  id: number;
  username: string;
  role: string;
}

interface AuthState {
  user: User | null;
  loading: boolean;
  error: string | null;
}

export function useAuth() {
  const [state, setState] = useState<AuthState>({
    user: null,
    loading: true,
    error: null,
  });

  const login = useCallback(async (username: string, password: string) => {
    setState(s => ({ ...s, loading: true, error: null }));
    try {
      const { data } = await client.post('/auth/login', { username, password });
      if (data.code === '0000') {
        setTokens(data.data.accessToken, data.data.refreshToken);
        setState({ user: data.data.user, loading: false, error: null });
        return true;
      }
      setState(s => ({ ...s, loading: false, error: data.info || 'Login failed' }));
      return false;
    } catch (err: any) {
      setState(s => ({
        ...s,
        loading: false,
        error: err.response?.data?.info || 'Network error',
      }));
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    clearTokens();
    setState({ user: null, loading: false, error: null });
    window.location.href = '/login';
  }, []);

  useEffect(() => {
    const token = getAccessToken();
    if (token) {
      try {
        const payload = JSON.parse(atob(token.split('.')[1]));
        setState({
          user: { id: parseInt(payload.sub), username: payload.username, role: payload.role },
          loading: false,
          error: null,
        });
      } catch {
        setState(s => ({ ...s, loading: false }));
      }
    } else {
      setState(s => ({ ...s, loading: false }));
    }
  }, []);

  return { ...state, login, logout };
}
