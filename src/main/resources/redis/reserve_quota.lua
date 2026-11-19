-- 原子預占使用者配額，同步維護計數、占用臺帳、逾期集合與冪等映射。
-- KEYS: meta, count, occupied, pending, cancel, idem。
-- ARGV: generation, token, loanId, requestId, fingerprint, maximum, expiresAtMillis。
local meta = KEYS[1]
local count = KEYS[2]
local occupied = KEYS[3]
local pending = KEYS[4]
local cancel = KEYS[5]
local idem = KEYS[6]
local generation = ARGV[1]
local token = ARGV[2]
local loan = ARGV[3]
local request = ARGV[4]
local fingerprint = ARGV[5]
local maximum = tonumber(ARGV[6])
local expires = tonumber(ARGV[7])

if redis.call('HGET', meta, 'state') ~= 'READY' or redis.call('HGET', meta, 'generation') ~= generation then
  return 'NOT_READY'
end
if redis.call('EXISTS', cancel) == 1 then return 'CANCELLED' end
local current = redis.call('HGET', occupied, loan)
if current then
  if current == token .. '|PENDING' or current == token .. '|COMMITTED' then return 'IDEMPOTENT' end
  return 'UNKNOWN'
end
local used = tonumber(redis.call('GET', count) or '0')
if used >= maximum then return 'LIMIT_EXCEEDED' end
redis.call('HSET', occupied, loan, token .. '|PENDING')
redis.call('ZADD', pending, expires, token .. '|' .. loan)
redis.call('SET', count, used + 1)
redis.call('SET', idem, request .. '|' .. loan .. '|' .. token .. '|' .. fingerprint)
redis.call('PEXPIREAT', idem, expires + 604800000)
return 'APPLIED'
