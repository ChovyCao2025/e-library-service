-- 原子預占書籍席位，並維護同一使用者同一本書只能有一筆活躍借閱的映射。
-- KEYS: meta, available, occupied, userLoan, pending, cancel。
-- ARGV: generation, userId, loanId, token, expiresAtMillis。
local meta = KEYS[1]
local available = KEYS[2]
local occupied = KEYS[3]
local userLoan = KEYS[4]
local pending = KEYS[5]
local cancel = KEYS[6]
local generation = ARGV[1]
local user = ARGV[2]
local loan = ARGV[3]
local token = ARGV[4]
local expires = tonumber(ARGV[5])

if redis.call('HGET', meta, 'state') ~= 'READY' or redis.call('HGET', meta, 'generation') ~= generation then
  return 'NOT_READY'
end
if redis.call('EXISTS', cancel) == 1 then return 'CANCELLED' end
local current = redis.call('HGET', occupied, loan)
if current then
  if current == user .. '|' .. token .. '|PENDING' or current == user .. '|' .. token .. '|COMMITTED' then return 'IDEMPOTENT' end
  return 'UNKNOWN'
end
local existingLoan = redis.call('HGET', userLoan, user)
if existingLoan and existingLoan ~= loan then return 'ALREADY_BORROWED' end
local remaining = tonumber(redis.call('GET', available) or '0')
if remaining <= 0 then return 'SOLD_OUT' end
redis.call('HSET', occupied, loan, user .. '|' .. token .. '|PENDING')
redis.call('HSET', userLoan, user, loan)
redis.call('ZADD', pending, expires, token .. '|' .. loan)
redis.call('SET', available, remaining - 1)
return 'APPLIED'
