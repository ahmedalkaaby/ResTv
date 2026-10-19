<?php

use App\Http\Controllers\MatchesController;
use Illuminate\Support\Facades\Route;

Route::get('/', fn () => response()->json(['service' => 'restv-server', 'ok' => true]));

// Restv: today's matches and their TV channels. Needs header X-Restv-Key (or ?key=).
Route::get('/api/matches', [MatchesController::class, 'index'])
    ->withoutMiddleware([
        \Illuminate\Session\Middleware\StartSession::class,
        \Illuminate\View\Middleware\ShareErrorsFromSession::class,
        \Illuminate\Foundation\Http\Middleware\ValidateCsrfToken::class,
    ]);
