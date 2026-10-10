import axios from 'axios';
import { installDemoAdapter } from '../demo/demoMode';

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api';

const api = axios.create({ baseURL });

installDemoAdapter(api);

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('yr_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('yr_token');
      localStorage.removeItem('yr_user');
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

export function extractErrorMessage(error) {
  const data = error?.response?.data;
  if (!data) return 'Something went wrong. Please try again.';
  if (typeof data.data === 'object' && data.data !== null && !Array.isArray(data.data)) {
    const firstKey = Object.keys(data.data)[0];
    if (firstKey) return data.data[firstKey];
  }
  return data.message || 'Something went wrong. Please try again.';
}

export default api;
