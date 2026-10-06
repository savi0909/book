-- Read-only aggregate audit; pass -v run_id=the16hexFixtureRunId. No bodies or row dumps.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '3s';
SELECT 'generic_valid_owner_duplicates' AS invariant, count(*) AS violations FROM (
  SELECT event_id,seat_number FROM bookings
  WHERE state='CONFIRMED' OR (state IN ('HELD','CHECKOUT') AND expires_at > transaction_timestamp())
  GROUP BY event_id,seat_number HAVING count(*) > 1
) violations;
SELECT 'run_duplicate_keys' AS invariant, count(*) AS violations FROM (
  SELECT buyer_id,hold_key FROM bookings WHERE hold_key LIKE 'hold:' || :'run_id' || ':%'
  GROUP BY buyer_id,hold_key HAVING count(*) > 1
) violations;
SELECT 'run_deadline_extension' AS invariant, count(*) AS violations FROM bookings
WHERE hold_key LIKE 'hold:' || :'run_id' || ':%'
AND abs(extract(epoch FROM expires_at-created_at)-ttl_seconds) > 0.1;
SELECT 'run_multiple_hold_audits' AS invariant, count(*) AS violations FROM (
  SELECT b.id FROM bookings b LEFT JOIN booking_audit a ON a.booking_id=b.id AND a.action='HELD'
  WHERE b.hold_key LIKE 'hold:' || :'run_id' || ':%'
  GROUP BY b.id HAVING count(a.id) <> 1
) violations;
SELECT 'movie_valid_member_ownership' AS invariant, count(*) AS violations
FROM movie_bookings b JOIN movie_booking_seats m ON m.booking_id=b.id
JOIN movie_show_seats s ON s.show_id=m.show_id AND s.seat_number=m.seat_number
WHERE (b.state='CONFIRMED' OR (b.state IN ('HELD','PAYMENT_PENDING') AND b.expires_at > transaction_timestamp()))
AND s.active_booking_id IS DISTINCT FROM b.id;
SELECT 'movie_confirmed_member_count' AS invariant, count(*) AS violations FROM (
  SELECT b.id FROM movie_bookings b LEFT JOIN movie_booking_seats m ON m.booking_id=b.id
  WHERE b.state='CONFIRMED' GROUP BY b.id HAVING count(m.seat_number) NOT BETWEEN 1 AND 10
) violations;
SELECT 'run_booking_counts' AS observation,state,count(*) FROM bookings
WHERE hold_key LIKE 'hold:' || :'run_id' || ':%' GROUP BY state ORDER BY state;
COMMIT;
