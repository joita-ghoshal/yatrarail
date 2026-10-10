import ConsoleLayout from './ConsoleLayout';

const LINKS = [
  { to: '/admin', label: 'Dashboard', end: true, icon: 'dashboard' },
  { to: '/admin/users', label: 'Users', icon: 'users' },
  { to: '/admin/trains', label: 'Trains', icon: 'train' },
  { to: '/admin/stations', label: 'Stations', icon: 'station' },
  { to: '/admin/fares', label: 'Fare Rules', icon: 'rupee' },
  { to: '/admin/quotas', label: 'Quotas', icon: 'scale' },
  { to: '/admin/bookings', label: 'Bookings', icon: 'ticket' },
  { to: '/admin/reconciliation', label: 'Payments', icon: 'card' },
  { to: '/admin/reports', label: 'Reports', icon: 'chart' },
  { to: '/admin/audit-logs', label: 'Audit Trail', icon: 'audit' },
  { to: '/admin/verify-ticket', label: 'Verify Ticket', icon: 'scan' },
  { to: '/admin/announcements', label: 'Announcements', icon: 'megaphone' },
];

export default function AdminLayout() {
  return <ConsoleLayout title="Admin Console" navLabel="Admin console sections" links={LINKS} />;
}