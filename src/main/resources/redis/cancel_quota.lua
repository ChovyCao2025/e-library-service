-- 取消尚未提交的配額預占；只有實際移除 PENDING 占用時才遞減計數。
-- KEYS: occupied, pending, count, cancel；ARGV: loanId, token。
redis.call('SET', KEYS[4], '1', 'PX', 604800000)
local value = redis.call('HGET', KEYS[1], ARGV[1])
if value == ARGV[2] .. '|PENDING' then
  redis.call('HDEL', KEYS[1], ARGV[1])
  redis.call('ZREM', KEYS[2], ARGV[2] .. '|' .. ARGV[1])
  local used = tonumber(redis.call('GET', KEYS[3]) or '0')
  redis.call('SET', KEYS[3], math.max(used - 1, 0))
  return 'APPLIED'
end
if not value then return 'IDEMPOTENT' end
return 'CANCELLED'
