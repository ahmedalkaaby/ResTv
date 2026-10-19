<?php

use App\Http\Controllers\MatchesController;
use Illuminate\Support\Facades\Route;

// The "api" group has no sessions or CSRF; every URL here starts with /api.
// Both need header X-Restv-Key (or ?key=) matching RESTV_KEY in .env.

// GET /api/matches?date=YYYY-MM-DD  → the saved matches for that day
Route::get('/matches', [MatchesController::class, 'index']);

// GET /api/matches/refresh  → fetch now (for cron-job.org if the host's cron is awkward)
Route::get('/matches/refresh', [MatchesController::class, 'refresh']);
