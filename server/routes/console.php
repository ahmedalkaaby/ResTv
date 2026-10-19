<?php

use Illuminate\Support\Facades\Schedule;

// Fetch today's and tomorrow's matches twice a day (Baghdad time).
// Needs the cron line from README.md: * * * * * php artisan schedule:run
Schedule::command('restv:matches --days=2')
    ->twiceDaily(5, 15)
    ->timezone('Asia/Baghdad')
    ->withoutOverlapping();
