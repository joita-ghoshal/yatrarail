import { Routes, Route } from 'react-router-dom';
import Navbar from './components/Navbar';
import DemoBanner from './components/DemoBanner';
import ProtectedRoute from './components/ProtectedRoute';
import AdminLayout from './components/AdminLayout';
import StaffLayout from './components/StaffLayout';

import Landing from './pages/Landing';
import About from './pages/About';
import Login from './pages/Login';
import Register from './pages/Register';
import ForgotPassword from './pages/ForgotPassword';
import PassengerDashboard from './pages/PassengerDashboard';
import SearchTrains from './pages/SearchTrains';
import TrainDetails from './pages/TrainDetails';
import BookingFlow from './pages/BookingFlow';
import MyBookings from './pages/MyBookings';
import BookingDetails from './pages/BookingDetails';
import TicketPage from './pages/TicketPage';
import PaymentsPage from './pages/PaymentsPage';
import Notifications from './pages/Notifications';
import Profile from './pages/Profile';
import NotFound from './pages/NotFound';

import AdminDashboard from './pages/admin/AdminDashboard';
import AdminUsers from './pages/admin/AdminUsers';
import AdminTrains from './pages/admin/AdminTrains';
import AdminStations from './pages/admin/AdminStations';
import AdminFares from './pages/admin/AdminFares';
import AdminQuotas from './pages/admin/AdminQuotas';
import AdminBookings from './pages/admin/AdminBookings';
import AdminReconciliation from './pages/admin/AdminReconciliation';
import AdminVerifyTicket from './pages/admin/AdminVerifyTicket';
import AdminAnnouncements from './pages/admin/AdminAnnouncements';
import AdminAuditLog from './pages/admin/AdminAuditLog';
import AdminReports from './pages/admin/AdminReports';

import StaffDashboard from './pages/staff/StaffDashboard';
import StaffManifest from './pages/staff/StaffManifest';
import StaffVerifyTicket from './pages/staff/StaffVerifyTicket';
import StaffIncidents from './pages/staff/StaffIncidents';
import StaffAssistance from './pages/staff/StaffAssistance';

export default function App() {
  return (
    <>
      {/* The first thing in the tab order on every page. There was no skip link
          anywhere in the application, so a keyboard user had to traverse the
          full navigation on every single page change. */}
      <a className="skip-link" href="#main-content">Skip to main content</a>

      <DemoBanner />

      <Navbar />

      {/* Exactly one main landmark per page, owned here rather than by each page,
          so it cannot end up duplicated or missing. tabIndex is what lets the
          skip link actually move focus here rather than only scrolling. */}
      <main id="main-content" className="page-main" tabIndex={-1}>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/about" element={<About />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/search" element={<SearchTrains />} />
          <Route path="/trains/:trainId" element={<TrainDetails />} />

          <Route path="/dashboard" element={<ProtectedRoute allowedRoles={['PASSENGER', 'TEMP_PASSENGER']}><PassengerDashboard /></ProtectedRoute>} />
          <Route path="/book/:trainId" element={<ProtectedRoute allowedRoles={['PASSENGER', 'TEMP_PASSENGER']}><BookingFlow /></ProtectedRoute>} />
          <Route path="/bookings" element={<ProtectedRoute allowedRoles={['PASSENGER', 'TEMP_PASSENGER']}><MyBookings /></ProtectedRoute>} />
          <Route path="/bookings/:bookingId" element={<ProtectedRoute allowedRoles={['PASSENGER', 'TEMP_PASSENGER']}><BookingDetails /></ProtectedRoute>} />
          <Route path="/tickets/:bookingId" element={<ProtectedRoute allowedRoles={['PASSENGER', 'TEMP_PASSENGER']}><TicketPage /></ProtectedRoute>} />
          <Route path="/payments" element={<ProtectedRoute allowedRoles={['PASSENGER', 'TEMP_PASSENGER']}><PaymentsPage /></ProtectedRoute>} />
          <Route path="/notifications" element={<ProtectedRoute><Notifications /></ProtectedRoute>} />
          <Route path="/profile" element={<ProtectedRoute><Profile /></ProtectedRoute>} />

          <Route path="/admin" element={<ProtectedRoute allowedRoles={['ADMIN']}><AdminLayout /></ProtectedRoute>}>
            <Route index element={<AdminDashboard />} />
            <Route path="users" element={<AdminUsers />} />
            <Route path="trains" element={<AdminTrains />} />
            <Route path="stations" element={<AdminStations />} />
            <Route path="fares" element={<AdminFares />} />
            <Route path="quotas" element={<AdminQuotas />} />
            <Route path="bookings" element={<AdminBookings />} />
            <Route path="reconciliation" element={<AdminReconciliation />} />
            <Route path="verify-ticket" element={<AdminVerifyTicket />} />
            <Route path="announcements" element={<AdminAnnouncements />} />
            <Route path="audit-logs" element={<AdminAuditLog />} />
            <Route path="reports" element={<AdminReports />} />
          </Route>

          <Route path="/staff" element={<ProtectedRoute allowedRoles={['STAFF', 'ADMIN']}><StaffLayout /></ProtectedRoute>}>
            <Route index element={<StaffDashboard />} />
            <Route path="manifest" element={<StaffManifest />} />
            <Route path="verify-ticket" element={<StaffVerifyTicket />} />
            <Route path="incidents" element={<StaffIncidents />} />
            <Route path="assistance" element={<StaffAssistance />} />
            {/* Staff operational alerts land in the same inbox as every other notification, so this
                reuses the shared page rather than shipping a second, subtly different reader. */}
            <Route path="notifications" element={<Notifications embedded />} />
          </Route>

          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </>
  );
}
