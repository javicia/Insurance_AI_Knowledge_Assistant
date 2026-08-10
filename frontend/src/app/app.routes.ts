import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'assistant' },
  {
    path: 'assistant',
    loadComponent: () => import('./features/assistant/pages/assistant-page/assistant-page').then((m) => m.AssistantPage),
    title: 'AI Assistant · Insurance AI Assistant',
  },
  {
    path: 'documents',
    loadComponent: () => import('./features/documents/pages/documents-page/documents-page').then((m) => m.DocumentsPage),
    title: 'Documents · Insurance AI Assistant',
  },
  {
    path: 'governance',
    loadComponent: () => import('./features/governance/pages/governance-page/governance-page').then((m) => m.GovernancePage),
    title: 'Governance · Insurance AI Assistant',
  },
  {
    path: 'audit',
    loadComponent: () => import('./features/audit/pages/audit-page/audit-page').then((m) => m.AuditPage),
    title: 'Audit · Insurance AI Assistant',
  },
  {
    path: 'evaluation',
    loadComponent: () => import('./features/evaluation/pages/evaluation-page/evaluation-page').then((m) => m.EvaluationPage),
    title: 'Evaluation · Insurance AI Assistant',
  },
  { path: '**', redirectTo: 'assistant' },
];
