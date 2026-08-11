import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'assistant' },
  {
    path: 'assistant',
    canActivate: [authGuard],
    loadComponent: () => import('./features/assistant/pages/assistant-page/assistant-page').then((m) => m.AssistantPage),
    title: 'AI Assistant · Insurance AI Assistant',
  },
  {
    path: 'documents',
    canActivate: [authGuard],
    loadComponent: () => import('./features/documents/pages/documents-page/documents-page').then((m) => m.DocumentsPage),
    title: 'Documents · Insurance AI Assistant',
  },
  {
    path: 'governance',
    canActivate: [authGuard],
    loadComponent: () => import('./features/governance/pages/governance-page/governance-page').then((m) => m.GovernancePage),
    title: 'Governance · Insurance AI Assistant',
  },
  {
    path: 'audit',
    canActivate: [authGuard],
    loadComponent: () => import('./features/audit/pages/audit-page/audit-page').then((m) => m.AuditPage),
    title: 'Audit · Insurance AI Assistant',
  },
  {
    path: 'evaluation',
    canActivate: [authGuard],
    loadComponent: () => import('./features/evaluation/pages/evaluation-page/evaluation-page').then((m) => m.EvaluationPage),
    title: 'Evaluation · Insurance AI Assistant',
  },
  { path: '**', redirectTo: 'assistant' },
];
