import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

export const playerTokenGuard: CanActivateFn = (route) => {
  const roomCode = route.paramMap.get('roomCode');
  const stored = localStorage.getItem(`promptle_player_${roomCode}`);
  if (!stored) {
    return inject(Router).createUrlTree(['/join', roomCode]);
  }
  return true;
};
