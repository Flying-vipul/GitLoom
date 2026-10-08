package devPilot.backend.controllers;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Lightweight health check endpoint — used by cron-job.org to ping the server
 * every 14 minutes so Render Free Tier never goes to sleep (cold start bypass).
 * Setup:
 *  1. Deploy this to Render.
 *  2. Go to https://cron-job.org → Create cronjob
 *     URL:  https://your-backend.onrender.com/api/health
 *     Schedule: Every 14 minutes
 *  3. That's it — server stays warm 24/7 within Render's 750 free hours/month.
 */
@RestController
public class HealthController {


    @GetMapping("/api/health")
    public ResponseEntity<String> keepAwake() {
        return ResponseEntity.ok("UP");
    }

}

