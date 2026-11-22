import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { vi } from 'vitest';

import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
  function setup(isAuthenticated: boolean) {
    const login = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: (() => isAuthenticated) as unknown as AuthService['isAuthenticated'],
            login,
          },
        },
        { provide: Router, useValue: {} },
      ],
    });
    return { login };
  }

  it('allows navigation when the user is authenticated', () => {
    setup(true);

    const result = TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));

    expect(result).toBe(true);
  });

  it('redirects to login and blocks navigation when the user is not authenticated', () => {
    const { login } = setup(false);

    const result = TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));

    expect(result).toBe(false);
    expect(login).toHaveBeenCalledTimes(1);
  });
});
