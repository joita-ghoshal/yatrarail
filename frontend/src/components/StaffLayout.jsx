import ConsoleLayout from './ConsoleLayout';

/**
 * Staff portal shell. Same sidebar pattern as the admin console, but deliberately a different set
 * of links: staff work a journey day, so the first three entries are "today", "the train" and
 * "who is on it" rather than configuration.
 */
const LINKS = [
  { to: '/staff', label: 'Dashboard', end: true, icon: 'dashboard' },
  { to: '/staff/manifest', label: 'Manifest & Boarding', icon: 'clipboard' },
  { to: '/staff/verify-ticket', label: 'Verify Ticket', icon: 'scan' },
  { to: '/staff/incidents', label: 'Incidents', icon: 'alert' },
  { to: '/staff/assistance', label: 'Assistance', icon: 'help' },
  { to: '/staff/notifications', label: 'Notifications', icon: 'bell' },
];

export default function StaffLayout() {
  return <ConsoleLayout title="Staff Portal" navLabel="Staff portal sections" links={LINKS} />;
}