-- 將配額占用由 PENDING 轉為 COMMITTED；取消標記優先於遲到確認。
-- KEYS: meta, occupied, pending, cancel；ARGV: generation, loanId, token。
if redis.call('HGET', KEYS[1], 'state') ~= 'READY' or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then return 'NOT_READY' end
if redis.call('EXISTS', KEYS[4]) == 1 then return 'CANCELLED' end
local value = redis.call('HGET', KEYS[2], ARGV[2])
if value == ARGV[3] .. '|COMMITTED' then return 'IDEMPOTENT' end
if value ~= ARGV[3] .. '|PENDING' then return 'UNKNOWN' end
redis.call('HSET', KEYS[2], ARGV[2], ARGV[3] .. '|COMMITTED')
redis.call('ZREM', KEYS[3], ARGV[3] .. '|' .. ARGV[2])
return 'APPLIED'
