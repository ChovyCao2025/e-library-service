-- 取消尚未提交的庫存預占；只回補與指定 userId、loanId、token 相符的席位。
-- KEYS: occupied, userLoan, pending, available, cancel；ARGV: userId, loanId, token。
redis.call('SET', KEYS[5], '1', 'PX', 604800000)
local value = redis.call('HGET', KEYS[1], ARGV[2])
if value == ARGV[1] .. '|' .. ARGV[3] .. '|PENDING' then
  redis.call('HDEL', KEYS[1], ARGV[2])
  redis.call('ZREM', KEYS[3], ARGV[3] .. '|' .. ARGV[2])
  local currentLoan = redis.call('HGET', KEYS[2], ARGV[1])
  if currentLoan == ARGV[2] then redis.call('HDEL', KEYS[2], ARGV[1]) end
  redis.call('INCR', KEYS[4])
  return 'APPLIED'
end
if not value then return 'IDEMPOTENT' end
return 'CANCELLED'
