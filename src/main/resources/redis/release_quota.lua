-- 釋放已提交的使用者配額；重複釋放在占用不存在時回傳 IDEMPOTENT。
-- KEYS: meta, occupied, count；ARGV: generation, loanId。
if redis.call('HGET', KEYS[1], 'state') ~= 'READY' or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then return 'NOT_READY' end
local value = redis.call('HGET', KEYS[2], ARGV[2])
if not value then return 'IDEMPOTENT' end
if string.sub(value, -10) ~= '|COMMITTED' then return 'UNKNOWN' end
redis.call('HDEL', KEYS[2], ARGV[2])
local used = tonumber(redis.call('GET', KEYS[3]) or '0')
redis.call('SET', KEYS[3], math.max(used - 1, 0))
return 'APPLIED'
