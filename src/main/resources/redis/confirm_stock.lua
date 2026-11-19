-- 將庫存占用由 PENDING 轉為 COMMITTED，並驗證 userId、loanId 與 token。
-- KEYS: meta, occupied, pending, cancel；ARGV: generation, userId, loanId, token。
if redis.call('HGET', KEYS[1], 'state') ~= 'READY' or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then return 'NOT_READY' end
if redis.call('EXISTS', KEYS[4]) == 1 then return 'CANCELLED' end
local pendingValue = ARGV[2] .. '|' .. ARGV[4] .. '|PENDING'
local committedValue = ARGV[2] .. '|' .. ARGV[4] .. '|COMMITTED'
local value = redis.call('HGET', KEYS[2], ARGV[3])
if value == committedValue then return 'IDEMPOTENT' end
if value ~= pendingValue then return 'UNKNOWN' end
redis.call('HSET', KEYS[2], ARGV[3], committedValue)
redis.call('ZREM', KEYS[3], ARGV[4] .. '|' .. ARGV[3])
return 'APPLIED'
