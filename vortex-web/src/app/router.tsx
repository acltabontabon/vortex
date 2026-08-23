import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from '../app-shell/AppShell';
import { PrintLayout } from '../app-shell/PrintLayout';
import { Home } from '../pages/Home';
import { NotFound } from '../pages/NotFound';
import { ServicesPage } from '../pages/ServicesPage';
import { NewServicePage } from '../pages/NewServicePage';
import { SettingsPage } from '../pages/SettingsPage';
import { RuntimePage } from '../pages/RuntimePage';
import { ServiceLayout } from '../features/service/ServiceLayout';
import { OverviewPage } from '../features/service/OverviewPage';
import { TestEditorPage } from '../features/service/TestEditorPage';
import { PreflightPage } from '../features/service/PreflightPage';
import { RunsPage } from '../features/service/RunsPage';
import { EvidencePage } from '../features/service/EvidencePage';
import { ConfigurationPage } from '../features/service/ConfigurationPage';
import { RunPage } from '../features/run/RunPage';
import { RunReportPage } from '../features/run/RunReportPage';
import { AllRunsPage } from '../features/run/AllRunsPage';
import { ComparePage } from '../features/run/ComparePage';
import { RouteErrorFallback } from './RouteErrorFallback';

// Grows page-by-page as the Thymeleaf migration proceeds. Root-mounted (no basename) — the SPA owns
// the whole origin in URL-space, distinct from vite.config.ts's base:'/app/', which is only the
// build output's *asset* path.
//
// A route reaches React the moment Spring stops mapping it literally: SpaController forwards
// anything unreserved to the app shell, and Spring always prefers a literal @GetMapping over that
// broad pattern.
export const router = createBrowserRouter([
  {
    element: <AppShell />,
    errorElement: <RouteErrorFallback />,
    children: [
      { path: '/', element: <Home /> },
      { path: '/services', element: <ServicesPage /> },
      { path: '/services/new', element: <NewServicePage /> },
      { path: '/settings', element: <SettingsPage /> },
      { path: '/runtime', element: <RuntimePage /> },
      // The old address for the same thing — vortex doctor is a command people already know.
      { path: '/doctor', element: <Navigate to="/runtime" replace /> },
      {
        path: '/services/:id',
        element: <ServiceLayout />,
        children: [
          { index: true, element: <OverviewPage /> },
          // Overview absorbed the test inventory (list, taxonomy, "Create test") — this only exists
          // so an old link or bookmark lands somewhere real instead of a 404.
          { path: 'tests', element: <Navigate to=".." replace /> },
          { path: 'tests/new', element: <TestEditorPage /> },
          { path: 'tests/:name/edit', element: <TestEditorPage /> },
          { path: 'run', element: <PreflightPage /> },
          { path: 'runs', element: <RunsPage /> },
          { path: 'evidence', element: <EvidencePage /> },
          { path: 'configuration', element: <ConfigurationPage /> },
        ],
      },
      // Not nested under /services/:id — a run outlives edits to the service it tested.
      { path: '/runs', element: <AllRunsPage /> },
      { path: '/runs/compare', element: <ComparePage /> },
      { path: '/runs/:id', element: <RunPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
  {
    element: <PrintLayout />,
    errorElement: <RouteErrorFallback />,
    children: [{ path: '/runs/:id/report', element: <RunReportPage /> }],
  },
]);
