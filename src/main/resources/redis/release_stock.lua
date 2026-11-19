-- 釋放已提交的書籍席位；值比對可避免舊借閱誤刪同一使用者的新借閱映射。
-- KEYS: meta, occupied, userLoan, available；ARGV: generation, userId, loanId。
if redis.call('HGET', KEYS[1], 'state') ~= 'READY' or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then return 'NOT_READY' end
local value = redis.call('HGET', KEYS[2], ARGV[3])
if not value then return 'IDEMPOTENT' end
if string.sub(value, 1, string.len(ARGV[2]) + 1) ~= ARGV[2] .. '|' or string.sub(value, -10) ~= '|COMMITTED' then return 'UNKNOWN' end
redis.call('HDEL', KEYS[2], ARGV[3])
local currentLoan = redis.call('HGET', KEYS[3], ARGV[2])
if currentLoan == ARGV[3] then redis.call('HDEL', KEYS[3], ARGV[2]) end
redis.call('INCR', KEYS[4])
return 'APPLIED'
