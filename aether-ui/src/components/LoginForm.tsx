import { useState, type FormEvent } from 'react';
import { useAuth } from '../hooks/useAuth';
import { escapeHtml } from '../utils/sanitize';

export function LoginForm() {
  const { login, loading, error } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const sanitizedUsername = escapeHtml(username.trim());
    await login(sanitizedUsername, password);
  };

  return (
    <form onSubmit={handleSubmit} style={{ maxWidth: 400, margin: '100px auto', padding: 20 }}>
      <h2>Aether Login</h2>
      {error && <div style={{ color: 'red', marginBottom: 10 }}>{escapeHtml(error)}</div>}
      <div style={{ marginBottom: 10 }}>
        <label>Username<br/>
          <input type="text" value={username} onChange={e => setUsername(e.target.value)}
            required minLength={3} maxLength={64} autoComplete="username"
            style={{ width: '100%', padding: 8 }} />
        </label>
      </div>
      <div style={{ marginBottom: 10 }}>
        <label>Password<br/>
          <input type="password" value={password} onChange={e => setPassword(e.target.value)}
            required minLength={8} maxLength={128} autoComplete="current-password"
            style={{ width: '100%', padding: 8 }} />
        </label>
      </div>
      <button type="submit" disabled={loading}
        style={{ width: '100%', padding: 10, cursor: 'pointer' }}>
        {loading ? 'Logging in...' : 'Login'}
      </button>
    </form>
  );
}
