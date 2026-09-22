local mforth = {}

-- ── Value tagging ──────────────────────────────────────────
-- A `table` value is a plain Lua table tagged via its metatable's __mtype
-- field rather than a visible data key, so the tag never collides with
-- real content and survives an empty table.

-- One shared metatable per kind, not a fresh one per value -- lets
-- __tostring be backfilled onto all of them later in this file, once
-- make_rational exists to build one (see table_tostring below). A field
-- added to an already-in-use metatable applies retroactively to every
-- value sharing it, since field lookups happen at access time.
local seq_mt = { __mtype = "seq" }
local map_mt = { __mtype = "map" }
local set_mt = { __mtype = "set" }
local tagged_mts = { seq = seq_mt, map = map_mt, set = set_mt }

local function tagged(kind)
  return setmetatable({}, tagged_mts[kind])
end

local function mtype(v)
  local mt = getmetatable(v)
  return mt and mt.__mtype
end

-- ── Display (pretty-printing) ──────────────────────────────
-- Confirmed against real Factor's own source (basis/prettyprint/backend):
-- `M: string pprint* dup "\"" "\"" pprint-string ;` -- strings print
-- QUOTED, escaped, because Factor's whole printing philosophy (stated
-- outright in its docs) is "print almost any object back as valid,
-- re-readable source". `display` is that: used by `.`/`.s`/nested
-- table elements. `print`/`write` (added below) are the separate, also
-- real, RAW-content words -- not the same thing as `.`.

local function quote_string(str)
  local escaped = str:gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n"):gsub("\t", "\\t")
  return '"' .. escaped .. '"'
end

local function display(v)
  if type(v) == "string" then return quote_string(v) end
  return tostring(v)
end

-- ── Rational numbers (exact) ───────────────────────────────
-- Exact fractions, always reduced to lowest terms with a positive
-- denominator, INTERNED so equal values are the same Lua table (Lua
-- hashes table keys by identity, not __eq -- required for correctness
-- when a Rational is used as a map/set/seq key, not just an optimization).

local function gcd(a, b)
  a, b = math.abs(a), math.abs(b)
  while b ~= 0 do a, b = b, a % b end
  return a
end

local rational_mt
rational_mt = {
  __mtype = "rational",
  __tostring = function(r) if r.den == 1 then return tostring(r.num) end return r.num .. "/" .. r.den end,
  __eq = function(a, b) return a.num == b.num and a.den == b.den end,
  __lt = function(a, b) return a.num * b.den < b.num * a.den end,
  __le = function(a, b) return a.num * b.den <= b.num * a.den end,
  __unm = function(a) return setmetatable({ num = -a.num, den = a.den }, rational_mt) end,
}

local rational_cache = {}

local function make_rational(num, den)
  den = den or 1
  if den == 0 then error("rational: division by zero") end
  if den < 0 then num, den = -num, -den end
  local g = gcd(num, den)
  if g ~= 0 then num, den = num / g, den / g end
  local key = num .. "/" .. den
  local cached = rational_cache[key]
  if cached then return cached end
  local r = setmetatable({ num = num, den = den }, rational_mt)
  rational_cache[key] = r
  return r
end

rational_mt.__add = function(a, b) return make_rational(a.num * b.den + b.num * a.den, a.den * b.den) end
rational_mt.__sub = function(a, b) return make_rational(a.num * b.den - b.num * a.den, a.den * b.den) end
rational_mt.__mul = function(a, b) return make_rational(a.num * b.num, a.den * b.den) end
rational_mt.__div = function(a, b) return make_rational(a.num * b.den, a.den * b.num) end

-- Backfilled onto seq_mt/map_mt/set_mt (declared before make_rational
-- existed to build this). Confirmed against real Factor rather than
-- invented: arrays print as `{ 1 2 3 }`, hashtables as
-- `H{ { "k" "v" } ... }` -- NOT a `k => v` arrow, which isn't Factor
-- syntax at all (an earlier version of this function invented that).
-- Nested elements go through `display`, not `tostring`, so a string
-- inside a seq/map is quoted too, same as Factor's own recursive pprint.
local function table_tostring(t)
  local kind = mtype(t)
  local parts = {}
  if kind == "seq" then
    local n = 0
    for _ in pairs(t) do n = n + 1 end
    for idx = 0, n - 1 do parts[#parts + 1] = display(t[make_rational(idx, 1)]) end
    if #parts == 0 then return "{ }" end
    return "{ " .. table.concat(parts, " ") .. " }"
  elseif kind == "set" then
    for k in pairs(t) do parts[#parts + 1] = display(k) end
    if #parts == 0 then return "{ }" end
    return "{ " .. table.concat(parts, " ") .. " }"
  else
    for k, v in pairs(t) do parts[#parts + 1] = "{ " .. display(k) .. " " .. display(v) .. " }" end
    if #parts == 0 then return "H{ }" end
    return "H{ " .. table.concat(parts, " ") .. " }"
  end
end
seq_mt.__tostring = table_tostring
map_mt.__tostring = table_tostring
set_mt.__tostring = table_tostring

-- ── Numeric tower: Rational (exact) + Float ────────────────
-- Float is a PLAIN LUA NUMBER, no wrapper at all -- Lua's own native
-- number type already is exactly what a Factor float needs, so this
-- costs nothing new. type(v) == "number" identifies it directly.
-- Generic dispatch: Rational (+) Rational stays exact; anything touching
-- a Float promotes -- matches Factor's own numeric-tower behavior, a
-- deliberate reversal of this project's earlier rationals-only phase.

local function is_rational(v) return mtype(v) == "rational" end
local function is_float(v) return type(v) == "number" end

local function to_float(v)
  if is_float(v) then return v end
  return v.num / v.den
end

local function both_rational(a, b) return is_rational(a) and is_rational(b) end

local function num_add(a, b) if both_rational(a, b) then return a + b end return to_float(a) + to_float(b) end
local function num_sub(a, b) if both_rational(a, b) then return a - b end return to_float(a) - to_float(b) end
local function num_mul(a, b) if both_rational(a, b) then return a * b end return to_float(a) * to_float(b) end
local function num_div(a, b) if both_rational(a, b) then return a / b end return to_float(a) / to_float(b) end
local function num_lt(a, b) if both_rational(a, b) then return a < b end return to_float(a) < to_float(b) end

local function values_eq(a, b)
  if both_rational(a, b) then return a == b end
  if is_float(a) or is_float(b) then
    if not (is_float(a) or is_rational(a)) or not (is_float(b) or is_rational(b)) then return false end
    return to_float(a) == to_float(b)
  end
  return a == b -- strings and identity for everything else
end

-- truncated toward zero -- shared by mod/`/mod` below.
local function trunc_div(fa, fb)
  local raw = fa / fb
  return raw >= 0 and math.floor(raw) or math.ceil(raw)
end

-- fraction (22/7), a genuine Float decimal literal (.25, 3.14159 -- real
-- Factor's own behavior; an earlier iteration of this kernel, inspired by
-- Fifth, treated decimals as exact-rational sugar instead -- reversed
-- here on purpose), or a plain integer (exact Rational). nil for anything
-- else -- resolve() then tries the dictionary lookup path instead.
local function parse_number(token)
  local n, d = token:match("^(%-?%d+)/(%d+)$")
  if n then return make_rational(tonumber(n), tonumber(d)) end

  if token:match("^%-?%d*%.%d*$") and token:match("%d") then
    return tonumber(token)
  end

  local i = token:match("^%-?%d+$")
  if i then return make_rational(tonumber(i), 1) end

  return nil
end

-- ── Booleans ───────────────────────────────────────────────
-- Factor's own model, not Forth's: a single `f` singleton is the only
-- false value; EVERYTHING else -- including 0, empty sequences, strings
-- -- counts as true. `t` is the canonical true singleton predicates push.
-- `f` also doubles as the generic "missing/absent" value (`at`/`nth` on
-- a missing key/index push `f`, not a special sentinel) -- matching
-- real Factor exactly, and meaning this kernel needs no separate NONE
-- marker the way an earlier version of it did.

local F = setmetatable({}, { __mtype = "f", __tostring = function() return "f" end })
local T = setmetatable({}, { __mtype = "t", __tostring = function() return "t" end })

local function is_true(v) return v ~= F end
local function bool(cond) return cond and T or F end

-- ── Stack ──────────────────────────────────────────────────

local function new_stack()
  return {}
end

local function push(stack, v)
  stack[#stack + 1] = v
end

local function pop(stack)
  local n = #stack
  if n == 0 then error("stack underflow") end
  local v = stack[n]
  stack[n] = nil
  return v
end

-- ── Dictionary / vocabularies ──────────────────────────────
-- Real Factor organizes words into named vocabularies (IN: sets where
-- new definitions land, USE:/USING: bring other vocabularies' words
-- into scope) instead of one flat namespace -- this is that, in-memory
-- only (no on-disk module/file loader -- a separate, much bigger
-- undertaking mforth has none of yet).
--
-- `dictionary` (the hand-written Lua primitives: dup, +, if, TUPLE:,
-- ...) IS the "kernel" vocabulary, always implicitly in scope from any
-- other vocabulary, exactly like real Factor's kernel vocab -- def()
-- is unchanged and keeps landing new primitives there directly.
-- Anything a user actually TYPES (:, TUPLE:'s generated words, CREATE,
-- DOES>, GENERIC:'s dispatcher) goes through define_word() instead,
-- landing in whichever vocabulary IN: currently points at.
--
-- Redefining a name just rebinds a table slot; that's safe for
-- already-compiled callers because compiling a definition resolves
-- each word it calls to its CURRENT function value once, at compile
-- time, and bakes that value directly into the new word's own
-- compiled body -- a caller never holds a live pointer back into any
-- of these tables, only the resolved function itself.
--
-- Word names stay exactly as typed (no case-folding).

local dictionary = {}

local function def(name, fn)
  dictionary[name] = fn
end

local vocabularies = { kernel = dictionary, scratchpad = {} }
local vocab_uses = { scratchpad = {} }
local current_vocab = "scratchpad" -- Factor's own default interactive vocabulary name

local function define_word(name, fn)
  vocabularies[current_vocab][name] = fn
end

-- current vocab's own words first (so a local definition always shadows
-- an imported or kernel one), then each USE:'d vocabulary, then kernel
-- last as the implicit, always-available fallback. Real Factor treats
-- a name defined in more than one used vocabulary as an ambiguity
-- error requiring qualification; this takes the simpler "first used
-- vocabulary wins" reading instead.
local function lookup(name)
  local own = vocabularies[current_vocab]
  if own and own[name] then return own[name] end
  local uses = vocab_uses[current_vocab]
  if uses then
    for used_name in pairs(uses) do
      local v = vocabularies[used_name]
      if v and v[name] then return v[name] end
    end
  end
  return dictionary[name]
end

local function make_wordref(name)
  local fn = lookup(name)
  if not fn then error("unknown word after \\ " .. tostring(name)) end
  return setmetatable({ fn = fn }, { __mtype = "wordref" })
end

-- Shared input cursor -- real Forth/Factor's own >IN mechanism. CREATE
-- (and, here, GENERIC:/TUPLE:/M:'s own name-reading) are runtime-parsing
-- words: whatever they read has to come from wherever the outer reader
-- currently is, however deep inside another word's execution that is,
-- not from the stack and not knowable at their caller's compile time.
local input = { tokens = nil, pos = 1 }
local create_state = { name = nil, area = nil, count = 0 }

-- Stack of currently-open `make` builders (core/make) -- a Lua-level
-- stack rather than a namespaces-style dynamic variable (this kernel
-- has no dynamic-scope mechanism), but the observable behavior is the
-- same: nested `make` calls each get their own builder, and `,`/`%`
-- always target the innermost currently-open one. Declared here, ahead
-- of `,` below, since `,` is shared between `make` and the older
-- CREATE/,/DOES> area-building below -- see the `,` definition itself.
local make_stack = {}

-- ── Generic words: class algebra dispatch ───────────────────────────
-- Confirmed against real Factor source (core/classes/classes.factor,
-- core/classes/tuple/tuple.factor) rather than invented -- real
-- Factor's actual specificity order is a general subsumption relation
-- (`class<=`), with `rank-class` as its own documented tiebreak:
-- tuple(concrete)=1, predicate=2, union=7, mixin=8, lower = more
-- specific = checked first. This kernel walks that same order
-- directly (concrete -> predicate-on-current-level -> union/mixin-of-
-- current-level -> superclass, repeat -> object) instead of
-- implementing real Factor's general subsumption algebra -- an honest
-- simplification, not a claimed exact match (INTERSECTION:/COMPLEMENT:/
-- anonymous class expressions aren't ported; they rank 3/4, between
-- tuple/predicate and union/mixin, so dropping them doesn't distort the
-- rest of the order). Still a real simplification versus Factor's own
-- multi-position dispatch too -- every generic word here dispatches on
-- one stack position (top by default, GENERIC# picks another).

local generics = {}
local generics_wired = {}
local run_ref -- assigned once `run` itself is defined, below

-- class_supers[class] = superclass -- TUPLE: `<` chain (single
-- inheritance). class_own_slots[class] = { own slot names } -- see
-- all_slots below for the inherited+own merge. Both populated by
-- TUPLE:'s parsing further down.
local class_supers = {}
local class_own_slots = {}

-- class_memberships[class] = { "mixin-or-union-name", ... }, in
-- declaration order -- a class's membership in any MIXIN:/UNION:
-- category, confirmed real Factor's own model (a mixin is a union you
-- populate incrementally via INSTANCE: instead of all at once, so both
-- share this one table -- core/classes/mixin/mixin.factor's own
-- mixin-class literally extends union-class). An ordered array, not a
-- set, so declaration order is checked deterministically, same as
-- generics[name] entries are found in the order they were defined.
local class_memberships = {}

local function register_membership(class, category)
  local list = class_memberships[class]
  if not list then list = {}; class_memberships[class] = list end
  for _, existing in ipairs(list) do if existing == category then return end end
  list[#list + 1] = category
end

-- class_predicates[predname] = { parent = parentclass, steps = compiled
-- body } -- PREDICATE:'s own definition, forward lookup by predicate
-- name. predicates_by_parent[parent] = { predname1, predname2, ... },
-- declaration order -- the REVERSE lookup resolve_method actually
-- walks: "which predicate classes narrow this level." Both populated
-- by PREDICATE:'s parsing further down.
local class_predicates = {}
local predicates_by_parent = {}

-- class_metatables[class] = its shared metatable -- populated by
-- define_tuple_class below (TUPLE:/ERROR:), so a runtime-supplied class
-- NAME (not a syntactic literal) can still build an instance --
-- confirmed real Factor's own `new`/`boa` are GENERIC words taking a
-- class value at runtime, not compile-time syntax. tuple_classes[class]
-- = true for every TUPLE:/ERROR:-declared class -- backs a universal
-- `tuple?` predicate real Factor gets from `BUILTIN: tuple` (every
-- TUPLE:/ERROR: instance is-a tuple, not just its own specific class).
local class_metatables = {}
local tuple_classes = {}

-- is_a(mt, target): is a value tagged mt an instance of target, either
-- directly, via the TUPLE: `<` chain, or via MIXIN:/UNION: membership
-- at any level of that chain -- e.g. is_a("dog", "animal") is true, so
-- `animal?` correctly answers t for a dog instance, not just for
-- `animal` itself; is_a("array", "sequence") is true once `INSTANCE:
-- array sequence` has registered that membership. Doesn't chase
-- membership-of-a-membership (a union containing another union) --
-- real Factor supports that, this kernel doesn't, an honest scope
-- limitation.
local function is_a(mt, target)
  local level = mt
  while level do
    if level == target then return true end
    local memberships = class_memberships[level]
    if memberships then
      for _, cat in ipairs(memberships) do
        if cat == target then return true end
      end
    end
    level = class_supers[level]
  end
  return false
end

-- all_slots(class): the full accessor/constructor slot list for class
-- -- every ancestor's own slots (root first), then class's own, so a
-- subclass's constructor takes its superclass's slots first, matching
-- real Factor's own `<dog>` behavior.
local function all_slots(class)
  local super = class_supers[class]
  local merged = super and all_slots(super) or {}
  local out = {}
  for _, sl in ipairs(merged) do out[#out + 1] = sl end
  for _, sl in ipairs(class_own_slots[class] or {}) do out[#out + 1] = sl end
  return out
end

local function dispatch_class(v)
  if type(v) == "number" then return "float" end
  if type(v) == "string" then return "string" end
  return mtype(v)
end

-- name -> how many items DOWN from the top the dispatching argument sits
-- (0 = top, real Factor's own GENERIC: default; set explicitly by
-- GENERIC# for anything else, e.g. a shape argument that isn't the last
-- one pushed before the call, as in `shape new-size resize`).
local generic_positions = {}

-- resolve_method: the walk described above. Every concrete class
-- (including plain builtin tags like "rational"/"seq", not just
-- TUPLE:-declared ones) falls through to "object" as a final level --
-- that's what makes `M: object default-impl ...` work as a universal
-- fallback, real Factor's own single most common generic-dispatch
-- idiom (confirmed throughout the already-fetched math.vectors source:
-- `M: object vneg [ neg ] map ;` and friends). Needs the actual
-- dispatching value `v` (not just its class), `s`/`ctx`, to run a
-- PREDICATE: class's test quotation -- pushed and popped around the
-- call so it doesn't disturb the real call stack (net zero: push v,
-- the quotation's own `( obj -- ? )` effect consumes it and leaves one
-- boolean, popped here).
local function resolve_method(name, class, v, s, ctx)
  local level = class
  while true do
    local impl = generics[name][level]
    if impl then return impl end
    local preds = predicates_by_parent[level]
    if preds then
      for _, predname in ipairs(preds) do
        push(s, v)
        run_ref(class_predicates[predname].steps, s, ctx)
        if is_true(pop(s)) then
          impl = generics[name][predname]
          if impl then return impl end
        end
      end
    end
    local memberships = class_memberships[level]
    if memberships then
      for _, cat in ipairs(memberships) do
        impl = generics[name][cat]
        if impl then return impl end
      end
    end
    if level == "object" then return nil end
    level = class_supers[level] or "object"
  end
end

local function wire_generic(name)
  if generics_wired[name] then return end
  generics_wired[name] = true
  generics[name] = generics[name] or {}
  define_word(name, function(s, ctx)
    local depth = generic_positions[name] or 0
    local idx = #s - depth
    if idx < 1 then error(name .. ": no dispatching argument at position " .. depth) end
    local v = s[idx]
    local class = dispatch_class(v)
    local impl = resolve_method(name, class, v, s, ctx)
    if not impl then error("no applicable method for '" .. name .. "' on class " .. tostring(class)) end
    run_ref(impl, s, ctx)
  end)
end

-- Counts a seq/map/set's own elements -- shared by length and by every
-- sequence word below that needs to know where the end is (last, tail,
-- reverse, append, sort, ...), plus each/map/reduce/filter/reject further
-- down. Hoisted here (was previously duplicated once inline in length and
-- once more as a local before the combinators) so there's exactly one
-- copy. Strings count as sequences too (real Factor's own `M: string`
-- sequence-protocol implementations) -- #seq is Lua's own O(1) byte count.
local function seq_len(seq)
  if type(seq) == "string" then return #seq end
  local n = 0
  for _ in pairs(seq) do n = n + 1 end
  return n
end

-- seq_get/seq_set: the one place every sequence word below reads/writes
-- a 0-based position, so strings-as-sequences (real Factor: `M: string
-- nth-unsafe`/etc.) only needed adding here, not in every word
-- individually. A string's "elements" are 1-character Lua strings, not
-- real Factor's own integer code-point characters -- this kernel has no
-- separate character type, an honest adaptation flagged rather than a
-- claimed exact match (same standard as sqrt/tuple-printing elsewhere
-- in this file). Lua strings are immutable, so seq_set on one fails
-- loudly rather than silently doing nothing.
local function seq_get(seq, idx)
  if type(seq) == "string" then
    local c = seq:sub(idx + 1, idx + 1)
    if c == "" then return nil end
    return c
  end
  return seq[make_rational(idx, 1)]
end

local function seq_set(seq, idx, v)
  if type(seq) == "string" then
    error("cannot mutate a string in place -- Lua strings are immutable")
  end
  seq[make_rational(idx, 1)] = v
end

-- ── Primitives ─────────────────────────────────────────────

def("t", function(s) push(s, T) end)
def("f", function(s) push(s, F) end)

def("dup", function(s) local v = pop(s); push(s, v); push(s, v) end)
def("drop", function(s) pop(s) end)
def("swap", function(s) local b, a = pop(s), pop(s); push(s, b); push(s, a) end)
def("over", function(s) local b, a = pop(s), pop(s); push(s, a); push(s, b); push(s, a) end)
def("rot", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, b); push(s, c); push(s, a)
end)
-- -rot ( a b c -- c a b ): rot's own inverse -- confirmed real Factor
-- kernel word, found while walking the kernel vocabulary listing.
def("-rot", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, c); push(s, a); push(s, b)
end)
-- nip ( a b -- b ): drops the SECOND item, keeping top -- confirmed
-- real Factor kernel word (the complement of drop, which keeps the
-- second item and drops the top).
def("nip", function(s) local b = pop(s); pop(s); push(s, b) end)
-- pick ( a b c -- a b c a ): copies the third-from-top item -- confirmed
-- real Factor kernel word, natural extension of dup/over.
def("pick", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, c); push(s, a)
end)
-- 2drop / 2dup ( a b -- ) / ( a b -- a b a b ): confirmed real Factor
-- kernel words, the two-at-a-time siblings of drop/dup.
def("2drop", function(s) pop(s); pop(s) end)
def("2dup", function(s)
  local b, a = pop(s), pop(s)
  push(s, a); push(s, b); push(s, a); push(s, b)
end)

-- Extra kernel shufflers -- confirmed real Factor stack effects, the
-- "d" (one-level-down) and 3/4/5-at-a-time siblings of the ones above.
def("tuck", function(s) local b, a = pop(s), pop(s); push(s, b); push(s, a); push(s, b) end)
-- 2over ( x y z -- x y z x y ): confirmed real Factor def, `pick pick`.
def("2over", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, c); push(s, a); push(s, b)
end)
def("spin", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, c); push(s, b); push(s, a)
end)
def("roll", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, b); push(s, c); push(s, d); push(s, a)
end)
def("-roll", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, d); push(s, a); push(s, b); push(s, c)
end)
def("reach", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, c); push(s, d); push(s, a)
end)
def("dupd", function(s) local b, a = pop(s), pop(s); push(s, a); push(s, a); push(s, b) end)
def("swapd", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, b); push(s, a); push(s, c)
end)
def("rotd", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, b); push(s, c); push(s, a); push(s, d)
end)
def("-rotd", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, c); push(s, a); push(s, b); push(s, d)
end)
def("overd", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, a); push(s, c)
end)
def("pickd", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, c); push(s, a); push(s, d)
end)
def("nipd", function(s)
  local c, b = pop(s), pop(s); pop(s)
  push(s, b); push(s, c)
end)
def("2nip", function(s) local c = pop(s); pop(s); pop(s); push(s, c) end)
def("2nipd", function(s)
  local d, c = pop(s), pop(s); pop(s); pop(s)
  push(s, c); push(s, d)
end)
def("3drop", function(s) pop(s); pop(s); pop(s) end)
def("3dup", function(s)
  local c, b, a = pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, c); push(s, a); push(s, b); push(s, c)
end)
def("3nip", function(s)
  local d = pop(s); pop(s); pop(s); pop(s)
  push(s, d)
end)
def("3nipd", function(s)
  local e, d = pop(s), pop(s); pop(s); pop(s); pop(s)
  push(s, d); push(s, e)
end)
def("4drop", function(s) pop(s); pop(s); pop(s); pop(s) end)
def("4dup", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, a); push(s, b); push(s, c); push(s, d)
  push(s, a); push(s, b); push(s, c); push(s, d)
end)
def("4nip", function(s)
  local e = pop(s); pop(s); pop(s); pop(s); pop(s)
  push(s, e)
end)
def("4spin", function(s)
  local d, c, b, a = pop(s), pop(s), pop(s), pop(s)
  push(s, d); push(s, c); push(s, b); push(s, a)
end)
def("5drop", function(s) pop(s); pop(s); pop(s); pop(s); pop(s) end)
def("5nip", function(s)
  local f = pop(s); pop(s); pop(s); pop(s); pop(s); pop(s)
  push(s, f)
end)

-- ? ( ? true false -- true/false ): confirmed real Factor kernel word
-- -- a ternary chooser between two already-evaluated VALUES (unlike
-- `if`, whose branches are quotations run lazily).
def("?", function(s)
  local false_v, true_v, cond = pop(s), pop(s), pop(s)
  push(s, is_true(cond) and true_v or false_v)
end)

-- eq? ( a b -- ? ): identity equality, confirmed real Factor kernel
-- word, distinct from = (value equality). Lua's own == already gives
-- reference identity for tables (seq/map/set/tuple values) and value
-- identity for numbers/strings/booleans, which lines up with Factor's
-- own eq? behavior on fixnums/strings closely enough for this kernel.
def("eq?", function(s) local b, a = pop(s), pop(s); push(s, bool(a == b)) end)

def("+", function(s) local b, a = pop(s), pop(s); push(s, num_add(a, b)) end)
def("-", function(s) local b, a = pop(s), pop(s); push(s, num_sub(a, b)) end)
def("*", function(s) local b, a = pop(s), pop(s); push(s, num_mul(a, b)) end)
def("/", function(s) local b, a = pop(s), pop(s); push(s, num_div(a, b)) end)

-- mod ( x y -- z ): confirmed against real Factor's own docs -- the
-- remainder takes the sign of the DIVIDEND x (truncated division), not
-- the divisor -- opposite of what an earlier version of this kernel
-- implemented (floored, Forth/Handbook convention) and NOT the same as
-- most other languages' own "mod". Factor has no separate "rem" word;
-- `/mod` gives quotient and remainder together instead.
def("mod", function(s)
  local b, a = pop(s), pop(s)
  local fa, fb = to_float(a), to_float(b)
  local q = trunc_div(fa, fb)
  if both_rational(a, b) then
    push(s, a - b * make_rational(q, 1))
  else
    push(s, fa - fb * q)
  end
end)
-- /mod ( x y -- z w ): z = truncated quotient, w = remainder (same sign
-- rule as mod above).
def("/mod", function(s)
  local b, a = pop(s), pop(s)
  local fa, fb = to_float(a), to_float(b)
  local q = trunc_div(fa, fb)
  if both_rational(a, b) then
    local qr = make_rational(q, 1)
    push(s, qr)
    push(s, a - b * qr)
  else
    push(s, q)
    push(s, fa - fb * q)
  end
end)

-- rem ( x y -- z ): confirmed real Factor math word via direct docs
-- fetch (docs.factorcode.org/content/word-rem,math.html) -- distinct
-- from mod above, always non-negative regardless of either operand's
-- sign (mod takes the sign of the dividend). This REVERSES an earlier,
-- mistaken note elsewhere in this project's history claiming Factor has
-- no separate rem word -- it does, with different sign behavior, not
-- the same word under a different name.
local function num_abs(v)
  if is_rational(v) then return v.num < 0 and make_rational(-v.num, v.den) or v end
  return math.abs(v)
end
def("rem", function(s)
  local b, a = pop(s), pop(s)
  local aa, ab = num_abs(a), num_abs(b)
  local fa, fb = to_float(aa), to_float(ab)
  local q = trunc_div(fa, fb) -- both operands non-negative, so trunc == floor here
  if both_rational(aa, ab) then
    push(s, aa - ab * make_rational(q, 1))
  else
    push(s, fa - fb * q)
  end
end)

-- neg/abs/sq ( x -- y ): confirmed real Factor math words. `-` works on
-- both Rational (rational_mt's own __unm) and Float (plain Lua negation)
-- already, so neg needs no per-kind branch.
def("neg", function(s) push(s, -pop(s)) end)
def("abs", function(s) push(s, num_abs(pop(s))) end)
def("sq", function(s) local x = pop(s); push(s, num_mul(x, x)) end)

-- zero?/neg?/even?/odd? ( x -- ? ): confirmed real Factor predicates.
-- Factor has no positive?/pos? word (confirmed absent) -- use `0 >`.
def("zero?", function(s) push(s, bool(values_eq(pop(s), make_rational(0, 1)))) end)
def("neg?", function(s) push(s, bool(num_lt(pop(s), make_rational(0, 1)))) end)
def("even?", function(s) push(s, bool(to_float(pop(s)) % 2 == 0)) end)
def("odd?", function(s) push(s, bool(to_float(pop(s)) % 2 ~= 0)) end)

-- min/max: real Factor's own generic definitions live in math.order
-- (`[ before? ] most` / `[ after? ] most`) -- defined there, below, once
-- <=>/before?/after?/most exist, so they work on anything comparable
-- (numbers AND strings), not just numbers.

-- gcd/lcm ( a b -- c ): confirmed real Factor words; reuse the same
-- Euclidean-algorithm helper already used to reduce Rationals.
def("gcd", function(s)
  local b, a = pop(s), pop(s)
  push(s, make_rational(gcd(to_float(a), to_float(b)), 1))
end)
def("lcm", function(s)
  local b, a = pop(s), pop(s)
  local fa, fb = to_float(a), to_float(b)
  local g = gcd(fa, fb)
  push(s, make_rational(g == 0 and 0 or math.abs(fa * fb) / g, 1))
end)

-- floor/ceiling/truncate/round ( x -- y ): confirmed real Factor words
-- and stack effects. Preserve exactness the same way +/-/*// already do
-- above -- a Rational input stays an exact (whole) Rational, a Float
-- input stays a Float, matching real Factor's own numeric-tower rule
-- rather than always collapsing to Float.
local function whole_result(was_rational, whole)
  if was_rational then return make_rational(whole, 1) end
  return whole
end
def("floor", function(s)
  local x = pop(s)
  push(s, whole_result(is_rational(x), math.floor(to_float(x))))
end)
def("ceiling", function(s)
  local x = pop(s)
  push(s, whole_result(is_rational(x), math.ceil(to_float(x))))
end)
def("truncate", function(s)
  local x = pop(s)
  local f = to_float(x)
  push(s, whole_result(is_rational(x), f >= 0 and math.floor(f) or math.ceil(f)))
end)
def("round", function(s)
  local x = pop(s)
  local f = to_float(x)
  local v = f >= 0 and math.floor(f + 0.5) or math.ceil(f - 0.5)
  push(s, whole_result(is_rational(x), v))
end)

-- sqrt ( x -- y ): confirmed real Factor word. Always returns a Float
-- here, even for a perfect-square Rational -- real Factor's own sqrt
-- can return an exact integer for a perfect square, which would need
-- exact-root detection this kernel doesn't have; an honest, flagged
-- simplification rather than a claimed exact match.
def("sqrt", function(s) push(s, math.sqrt(to_float(pop(s)))) end)

def("<", function(s) local b, a = pop(s), pop(s); push(s, bool(num_lt(a, b))) end)
def(">", function(s) local b, a = pop(s), pop(s); push(s, bool(num_lt(b, a))) end)
def("=", function(s) local b, a = pop(s), pop(s); push(s, bool(values_eq(a, b))) end)
-- and/or ( obj1 obj2 -- obj2/f / obj1/obj2 ): confirmed real Factor
-- stack effects -- these do NOT coerce to a fresh t/f the way an
-- earlier version of this kernel did. `and` keeps obj2 when obj1 is
-- true, else obj1 itself (which, being the only falsy value, IS f);
-- `or` keeps obj1 when it's already true, else falls through to obj2 --
-- Lisp-style value-passing and/or, not boolean AND/OR. Only `not`
-- always produces a genuine t/f.
def("and", function(s) local b, a = pop(s), pop(s); push(s, is_true(a) and b or a) end)
def("or", function(s) local b, a = pop(s), pop(s); push(s, is_true(a) and a or b) end)
def("not", function(s) push(s, bool(not is_true(pop(s)))) end)

-- and*/or* ( obj1 obj2 -- obj1/f / obj2/obj1 ): confirmed real Factor
-- -- and/or's "keep the OTHER operand" siblings, testing obj2's truth
-- instead of obj1's.
def("and*", function(s) local b, a = pop(s), pop(s); push(s, is_true(b) and a or b) end)
def("or*", function(s) local b, a = pop(s), pop(s); push(s, is_true(b) and b or a) end)

-- or? ( obj1 obj2 -- obj2/obj1 second? ): confirmed real Factor --
-- or*'s result, plus whether obj2 (the second) was the one selected.
def("or?", function(s)
  local b, a = pop(s), pop(s)
  if is_true(b) then push(s, b); push(s, T) else push(s, a); push(s, F) end
end)

-- xor ( obj1 obj2 -- obj1/obj2/f ): confirmed real Factor -- true
-- (returns whichever operand is true) when exactly one of obj1/obj2 is
-- true, f when both or neither are.
def("xor", function(s)
  local b, a = pop(s), pop(s)
  local ta, tb = is_true(a), is_true(b)
  if ta == tb then push(s, F)
  elseif ta then push(s, a)
  else push(s, b) end
end)

-- >boolean ( obj -- ? ): confirmed real Factor -- coerces to a genuine
-- t/f, the word and/or used to (wrongly) do implicitly.
def(">boolean", function(s) push(s, bool(is_true(pop(s)))) end)

-- boolean? ( object -- ? ): confirmed real Factor -- true for either
-- singleton, t or f. Real Factor expresses this as `UNION: boolean
-- POSTPONE: t POSTPONE: f ;`; written directly here rather than via
-- this kernel's own UNION: syntax so it lands in the always-available
-- dictionary table like every other kernel-vocab word in this file,
-- not whatever vocabulary happens to be current when the kernel loads.
def("boolean?", function(s)
  local c = dispatch_class(pop(s))
  push(s, bool(c == "t" or c == "f"))
end)
-- number? ( object -- ? ): confirmed real Factor -- true for either half
-- of the numeric tower, Rational or Float.
def("number?", function(s)
  local c = dispatch_class(pop(s))
  push(s, bool(c == "rational" or c == "float"))
end)
-- string>number ( str -- n/f ): confirmed real Factor stack effect (f,
-- not an error, on a string that isn't a valid number) -- reuses the
-- exact same parse_number the tokenizer itself already runs every
-- literal token through, so a digit-run parsed out of a notation AST
-- (an octave/duration digit run, "1".."999") converts exactly the same
-- way a literal typed at the mforth REPL would.
def("string>number", function(s)
  local v = pop(s)
  push(s, parse_number(v) or F)
end)
-- number>string ( n -- str ): confirmed real Factor stack effect --
-- reuses the Rational/Float __tostring metamethods already backing
-- write/print, so this renders exactly the same text a literal typed
-- at the REPL would print back.
def("number>string", function(s)
  push(s, tostring(pop(s)))
end)

-- ── math.order (comparator protocol) ────────────────────────
-- Confirmed against real Factor source (core/math/order/order.factor):
-- <=> returns one of three singleton symbols, and before?/after?/
-- before=?/after=?/min/max/clamp/between? are all defined generically
-- in terms of it -- not hand-rolled per kind. Scoped to the two
-- comparable kinds this kernel actually has (numbers, strings), same
-- honest limitation `sort` already had before this existed.
local LT = setmetatable({}, { __mtype = "symbol", __tostring = function() return "+lt+" end })
local EQ = setmetatable({}, { __mtype = "symbol", __tostring = function() return "+eq+" end })
local GT = setmetatable({}, { __mtype = "symbol", __tostring = function() return "+gt+" end })

def("+lt+", function(s) push(s, LT) end)
def("+eq+", function(s) push(s, EQ) end)
def("+gt+", function(s) push(s, GT) end)

local function compare3(a, b)
  if (is_rational(a) or is_float(a)) and (is_rational(b) or is_float(b)) then
    if num_lt(a, b) then return LT end
    if values_eq(a, b) then return EQ end
    return GT
  end
  if type(a) == "string" and type(b) == "string" then
    if a < b then return LT end
    if a == b then return EQ end
    return GT
  end
  error("<=>: cannot compare these element types")
end
def("<=>", function(s) local b, a = pop(s), pop(s); push(s, compare3(a, b)) end)

def("before?", function(s) local b, a = pop(s), pop(s); push(s, bool(compare3(a, b) == LT)) end)
def("after?", function(s) local b, a = pop(s), pop(s); push(s, bool(compare3(a, b) == GT)) end)
def("before=?", function(s) local b, a = pop(s), pop(s); push(s, bool(compare3(a, b) ~= GT)) end)
def("after=?", function(s) local b, a = pop(s), pop(s); push(s, bool(compare3(a, b) ~= LT)) end)

-- min/max ( obj1 obj2 -- obj ): now generic via before?/after? (real
-- Factor's own `M: object min [ before? ] most` / `max [ after? ]
-- most`) -- works on strings too, not just numbers.
def("min", function(s) local b, a = pop(s), pop(s); push(s, compare3(a, b) ~= GT and a or b) end)
def("max", function(s) local b, a = pop(s), pop(s); push(s, compare3(a, b) ~= LT and a or b) end)

-- clamp ( x min max -- y ): confirmed real Factor def, `min( max(x,min), max )`.
def("clamp", function(s)
  local hi, lo, x = pop(s), pop(s), pop(s)
  local floored = compare3(x, lo) ~= LT and x or lo
  push(s, compare3(floored, hi) ~= GT and floored or hi)
end)

-- between? ( x min max -- ? ): confirmed real Factor def -- x >= min and x <= max.
def("between?", function(s)
  local hi, lo, x = pop(s), pop(s), pop(s)
  push(s, bool(compare3(x, lo) ~= LT and compare3(x, hi) ~= GT))
end)

-- [-] ( x y -- z ): confirmed real Factor def, `- 0 max`.
def("[-]", function(s)
  local b, a = pop(s), pop(s)
  local diff = num_sub(a, b)
  push(s, num_lt(diff, make_rational(0, 1)) and make_rational(0, 1) or diff)
end)

-- `.` prints the FULL pretty-printed (quoted-string) form, with a
-- trailing newline -- confirmed real Factor behavior (M: string pprint*
-- quotes; `.` = pprint + newline). `print`/`write` (real io-vocab
-- words) print a string's RAW content instead -- not the same word,
-- unlike an earlier version of this kernel which conflated the two.
def(".", function(s) io.write(display(pop(s)), "\n") end)
def(".s", function(s)
  io.write("<", #s, "> ")
  for _, v in ipairs(s) do io.write(display(v), " ") end
  io.write("\n")
end)
def("print", function(s) io.write(tostring(pop(s)), "\n") end)
def("write", function(s) io.write(tostring(pop(s))) end)
def("depth", function(s) push(s, make_rational(#s, 1)) end)
-- clear ( ..a -- ): wipes the whole data stack -- real Factor's own
-- word for this, useful in a REPL when leftover values pile up.
def("clear", function(s)
  for i = #s, 1, -1 do s[i] = nil end
end)

-- Sequence protocol (real Factor names/argument order, confirmed
-- against docs.factorcode.org) -- NOT the invented "t-get"/"seq-new"
-- family an earlier version of this kernel had. The collection comes
-- LAST in every one of these, not first -- a genuinely different
-- convention from the tuple setters above, and real Factor's own.
def("nth", function(s)
  local seq = pop(s)
  local n = pop(s)
  local v = seq_get(seq, to_float(n))
  push(s, v == nil and F or v)
end)
def("set-nth", function(s)
  local seq = pop(s)
  local n = pop(s)
  local elt = pop(s)
  seq_set(seq, to_float(n), elt)
end)
def("length", function(s) push(s, make_rational(seq_len(pop(s)), 1)) end)

-- first/second/last ( seq -- elt ): confirmed real Factor sequence words
-- -- real Factor's own `first` is literally defined as `0 swap nth`.
def("first", function(s) local seq = pop(s); push(s, seq_get(seq, 0)) end)
def("second", function(s) local seq = pop(s); push(s, seq_get(seq, 1)) end)
def("last", function(s)
  local seq = pop(s)
  push(s, seq_get(seq, seq_len(seq) - 1))
end)

-- Shared by tail/head/rest below -- returns a new seq holding
-- seq[from..to_exclusive-1]. Reads via seq_get, so this also works when
-- seq is a plain Lua string, and the RESULT is still a genuine seq
-- (this kernel's tail/head/rest don't preserve "string in, string out",
-- same simplification `remove`/`concat` etc. below already accept).
local function seq_slice(seq, from, to_exclusive)
  local result = tagged("seq")
  local j = 0
  for idx = from, to_exclusive - 1 do
    result[make_rational(j, 1)] = seq_get(seq, idx)
    j = j + 1
  end
  return result
end

-- tail ( seq n -- tailseq ) / head ( seq n -- headseq ): confirmed real
-- Factor stack effects -- seq comes BEFORE n here, the opposite order
-- from nth/set-nth's "collection last" convention (a genuinely different
-- family, same as the tuple setters vs. set-nth split noted above).
def("tail", function(s)
  local n, seq = pop(s), pop(s)
  push(s, seq_slice(seq, to_float(n), seq_len(seq)))
end)
def("head", function(s)
  local n, seq = pop(s), pop(s)
  push(s, seq_slice(seq, 0, to_float(n)))
end)
-- rest ( seq -- tailseq ): confirmed real Factor word, defined in terms
-- of tail as `1 tail`.
def("rest", function(s)
  local seq = pop(s)
  push(s, seq_slice(seq, 1, seq_len(seq)))
end)

-- append ( seq1 seq2 -- newseq ): confirmed real Factor stack effect.
def("append", function(s)
  local seq2, seq1 = pop(s), pop(s)
  local result = tagged("seq")
  local n1 = seq_len(seq1)
  for idx = 0, n1 - 1 do result[make_rational(idx, 1)] = seq_get(seq1, idx) end
  local n2 = seq_len(seq2)
  for idx = 0, n2 - 1 do result[make_rational(n1 + idx, 1)] = seq_get(seq2, idx) end
  push(s, result)
end)

-- reverse ( seq -- newseq ): confirmed real Factor stack effect.
def("reverse", function(s)
  local seq = pop(s)
  local n = seq_len(seq)
  local result = tagged("seq")
  for idx = 0, n - 1 do result[make_rational(idx, 1)] = seq_get(seq, n - 1 - idx) end
  push(s, result)
end)

-- push ( elt seq -- ): confirmed real Factor stack effect -- appends
-- elt at the end of a mutable sequence, no output (same "collection
-- last, no return value" shape as set-nth/set-at above). seq_set errors
-- clearly if seq is a plain (immutable) Lua string.
def("push", function(s)
  local seq = pop(s)
  local elt = pop(s)
  seq_set(seq, seq_len(seq), elt)
end)

-- sort ( seq -- sortedseq ): confirmed real Factor stack effect --
-- natural order, via the real `<=>` three-way comparator now defined
-- above in math.order (was a private default_less covering the same
-- two comparable kinds before <=> existed).
def("sort", function(s)
  local seq = pop(s)
  local arr = {}
  for idx = 0, seq_len(seq) - 1 do arr[#arr + 1] = seq_get(seq, idx) end
  table.sort(arr, function(a, b) return compare3(a, b) == LT end)
  local result = tagged("seq")
  for idx, v in ipairs(arr) do result[make_rational(idx - 1, 1)] = v end
  push(s, result)
end)

-- Assoc protocol (real Factor names/argument order, same source).
def("at", function(s)
  local assoc = pop(s)
  local key = pop(s)
  local v = assoc[key]
  push(s, v == nil and F or v)
end)
def("set-at", function(s)
  local assoc = pop(s)
  local key = pop(s)
  local value = pop(s)
  assoc[key] = value
end)
def("delete-at", function(s)
  local assoc = pop(s)
  local key = pop(s)
  assoc[key] = nil
end)
def("key?", function(s)
  local assoc = pop(s)
  local key = pop(s)
  push(s, bool(assoc[key] ~= nil))
end)
-- Positional keys are RATIONALS, not plain Lua integers -- every number
-- literal typed in mforth source is a Rational or Float, never a bare
-- Lua integer, so a plain integer key would be unreachable from source.
def("keys", function(s)
  local assoc = pop(s)
  local ks = tagged("seq")
  local i = 0
  for k in pairs(assoc) do ks[make_rational(i, 1)] = k; i = i + 1 end
  push(s, ks)
end)
def("values", function(s)
  local assoc = pop(s)
  local vs = tagged("seq")
  local i = 0
  for _, v in pairs(assoc) do vs[make_rational(i, 1)] = v; i = i + 1 end
  push(s, vs)
end)

-- adjoin ( elt set -- ): confirmed real Factor word for set insertion.
-- A "set" here is just an assoc used for membership (key present ->
-- member), so at/set-at/delete-at/key? all already work on one too --
-- adjoin is the convenience that doesn't require picking a marker value.
def("adjoin", function(s)
  local set_t = pop(s)
  local elt = pop(s)
  set_t[elt] = T
end)

-- ── sets (core/sets) ─────────────────────────────────────────────
-- Scoped to this kernel's own hash-backed `set` type (real Factor's
-- generic union/etc. also work on plain sequences via INSTANCE:
-- sequence set, but its plain-sequence `union` doesn't dedupe -- only
-- append + a same-type set-like pass -- scoping to our own set type
-- keeps the honest, useful dedup-on-union behavior rather than
-- reproducing that sequence-specific quirk).

-- in? ( elt set -- ? ): confirmed real Factor word -- table lookup for
-- our set type, linear scan via values_eq for a plain seq (matching
-- real Factor's `M: sequence in? member?`).
def("in?", function(s)
  local set_t, elt = pop(s), pop(s)
  if mtype(set_t) == "set" then
    push(s, bool(set_t[elt] ~= nil))
    return
  end
  for idx = 0, seq_len(set_t) - 1 do
    if values_eq(set_t[make_rational(idx, 1)], elt) then push(s, T); return end
  end
  push(s, F)
end)

-- ?adjoin ( elt set -- ? ): confirmed real Factor def,
-- `2dup in? [ 2drop f ] [ adjoin t ] if`.
def("?adjoin", function(s)
  local set_t, elt = pop(s), pop(s)
  if set_t[elt] ~= nil then push(s, F)
  else set_t[elt] = T; push(s, T) end
end)

-- delete ( elt set -- ) / ?delete ( elt set -- ? ): confirmed real
-- Factor words, scoped to our own set type.
def("delete", function(s) local set_t, elt = pop(s), pop(s); set_t[elt] = nil end)
def("?delete", function(s)
  local set_t, elt = pop(s), pop(s)
  if set_t[elt] ~= nil then set_t[elt] = nil; push(s, T)
  else push(s, F) end
end)

-- members ( set -- seq ) / cardinality ( set -- n ): confirmed real
-- Factor words -- members is keys's set-specific name, cardinality is
-- length's.
def("members", function(s)
  local set_t = pop(s)
  local ks = tagged("seq")
  local i = 0
  for k in pairs(set_t) do ks[make_rational(i, 1)] = k; i = i + 1 end
  push(s, ks)
end)
def("cardinality", function(s) push(s, make_rational(seq_len(pop(s)), 1)) end)

-- union/intersect/diff/subset?/set= below all iterate pairs() over
-- their arguments directly -- correct for our `set` type (whose keys
-- ARE its elements), but silently wrong on a `seq`/`vector` (whose keys
-- are positional indices, not values). Guarded rather than left to fail
-- confusingly, since a music-DSL caller could easily reach for the
-- wrong sequence type here.
local function require_set(v)
  if mtype(v) ~= "set" then error("expected a set (built via set-new/adjoin), got a " .. tostring(mtype(v))) end
  return v
end

-- union/intersect/diff ( set1 set2 -- set ): confirmed real Factor
-- stack effects.
def("union", function(s)
  local set2, set1 = require_set(pop(s)), require_set(pop(s))
  local result = tagged("set")
  for k in pairs(set1) do result[k] = T end
  for k in pairs(set2) do result[k] = T end
  push(s, result)
end)
def("intersect", function(s)
  local set2, set1 = require_set(pop(s)), require_set(pop(s))
  local result = tagged("set")
  for k in pairs(set1) do if set2[k] ~= nil then result[k] = T end end
  push(s, result)
end)
def("diff", function(s)
  local set2, set1 = require_set(pop(s)), require_set(pop(s))
  local result = tagged("set")
  for k in pairs(set1) do if set2[k] == nil then result[k] = T end end
  push(s, result)
end)

-- subset?/set= ( set1 set2 -- ? ): confirmed real Factor stack effects.
def("subset?", function(s)
  local set2, set1 = require_set(pop(s)), require_set(pop(s))
  for k in pairs(set1) do if set2[k] == nil then push(s, F); return end end
  push(s, T)
end)
def("set=", function(s)
  local set2, set1 = require_set(pop(s)), require_set(pop(s))
  for k in pairs(set1) do if set2[k] == nil then push(s, F); return end end
  for k in pairs(set2) do if set1[k] == nil then push(s, F); return end end
  push(s, T)
end)

-- Constructors. <vector>/<hashtable> are real Factor names (confirmed
-- referenced in its own docs as an alternative to {}/H{} literal
-- syntax, which this kernel does not implement as INPUT syntax yet --
-- see the follow-up note in memory). Real Factor's versions may accept
-- an optional capacity hint; these don't need one, so they're bare.
-- set-new has no confirmed real Factor equivalent name found -- kept
-- as this kernel's own, flagged as such rather than guessed further.
def("<vector>", function(s) push(s, tagged("seq")) end)
def("<hashtable>", function(s) push(s, tagged("map")) end)
def("set-new", function(s) push(s, tagged("set")) end)

-- sequence? ( obj -- ? ): confirmed real Factor -- a string is a
-- sequence too (real Factor's own `M: string` sequence-protocol
-- implementations), not just this kernel's own seq/vector type.
def("sequence?", function(s) local v = pop(s); push(s, bool(mtype(v) == "seq" or type(v) == "string")) end)
def("assoc?", function(s) push(s, bool(mtype(pop(s)) == "map")) end)
def("set?", function(s) push(s, bool(mtype(pop(s)) == "set")) end)
def("table?", function(s)
  local k = mtype(pop(s))
  push(s, bool(k == "seq" or k == "map" or k == "set"))
end)
-- empty? ( obj -- ? ): confirmed real Factor -- `#seq == 0` for a
-- string, since strings have no metatable pairs() can walk.
def("empty?", function(s)
  local v = pop(s)
  if type(v) == "string" then push(s, bool(#v == 0)) else push(s, bool(next(v) == nil)) end
end)

def("execute", function(s)
  local ref = pop(s)
  if mtype(ref) ~= "wordref" then error("execute: not a word reference") end
  ref.fn(s)
end)

-- CREATE/,/DOES> -- Forth-specific, kept from the prior kernel phase
-- (no user request to remove them), superseded in spirit by the object
-- system below for new code, but not actively removed.
def("create", function()
  local tok = input.tokens[input.pos]
  if not tok or tok.kind ~= "word" then error("create: expected a name") end
  input.pos = input.pos + 1
  local area = tagged("seq")
  create_state.name, create_state.area, create_state.count = tok.text, area, 0
  define_word(tok.text, function(s2) push(s2, area) end)
end)

-- , ( elt -- ): confirmed real Factor stack effect -- appends to
-- whichever builder is currently open. Real Factor's own `,` (core/make)
-- appends to the innermost `make` scope; this kernel's older CREATE/,
-- convention (appends to CREATE's data area) predates that and still
-- has no real Factor equivalent, so a make builder takes priority when
-- one is open, falling back to CREATE's area otherwise -- the two never
-- overlap in practice (CREATE bodies don't call make, and vice versa).
def(",", function(s)
  if #make_stack > 0 then
    local builder = make_stack[#make_stack]
    seq_set(builder, seq_len(builder), pop(s))
    return
  end
  local area = create_state.area
  if not area then error(", used with no prior create or make") end
  area[make_rational(create_state.count, 1)] = pop(s)
  create_state.count = create_state.count + 1
end)

def("GENERIC:", function()
  local tok = input.tokens[input.pos]
  if not tok or tok.kind ~= "word" then error("GENERIC: expected a name") end
  input.pos = input.pos + 1
  wire_generic(tok.text)
end)

-- GENERIC# name depth ( stack-effect ) -- like GENERIC:, but dispatches
-- on the argument `depth` items down from the top instead of always the
-- top -- real Factor's own answer to "the dispatching object isn't the
-- last thing pushed," e.g. `GENERIC# resize 1 ( shape new-size -- )`
-- dispatches on shape even though new-size ends up on top at the call.
def("GENERIC#", function()
  local tok = input.tokens[input.pos]
  if not tok or tok.kind ~= "word" then error("GENERIC# expected a name") end
  input.pos = input.pos + 1
  local pos_tok = input.tokens[input.pos]
  local n = pos_tok and parse_number(pos_tok.text)
  if not n then error("GENERIC# expected a numeric dispatch position") end
  input.pos = input.pos + 1
  generic_positions[tok.text] = to_float(n)
  wire_generic(tok.text)
end)

-- IN: name -- new definitions land in this vocabulary from now on
-- (persists across separate eval() calls, i.e. separate REPL lines,
-- same as the dictionary itself already does).
def("IN:", function()
  local tok = input.tokens[input.pos]
  if not tok or tok.kind ~= "word" then error("IN: expected a vocabulary name") end
  input.pos = input.pos + 1
  current_vocab = tok.text
  vocabularies[current_vocab] = vocabularies[current_vocab] or {}
  vocab_uses[current_vocab] = vocab_uses[current_vocab] or {}
end)

local function use_vocab(name)
  vocab_uses[current_vocab] = vocab_uses[current_vocab] or {}
  vocab_uses[current_vocab][name] = true
end

-- USE: name -- brings one other vocabulary's words into scope for the
-- CURRENT vocabulary (an import, not a definition-target change).
def("USE:", function()
  local tok = input.tokens[input.pos]
  if not tok or tok.kind ~= "word" then error("USE: expected a vocabulary name") end
  input.pos = input.pos + 1
  use_vocab(tok.text)
end)

-- USING: name1 name2 ... ; -- several USE:s in one form. Real Factor
-- also has QUALIFIED:/FROM:/EXCLUDE:/RENAME: for finer-grained imports
-- -- not attempted here, this covers the common case only.
def("USING:", function()
  while true do
    local tok = input.tokens[input.pos]
    if not tok then error("USING: expected a terminating ';'") end
    if tok.text == ";" then input.pos = input.pos + 1; break end
    use_vocab(tok.text)
    input.pos = input.pos + 1
  end
end)

-- ── Tokenizer ──────────────────────────────────────────────
-- A real character scan, not a whitespace split -- needed because "..."
-- string literals can contain whitespace themselves, and (...) comments
-- must be told apart from ordinary tokens. (Forth's own S"/." string
-- convention was carried over from the pre-pivot kernel phase and has
-- since been retired -- real Factor has neither, just "..." literals.)

-- Real Factor's own actual string literal syntax: "foo", content starts
-- immediately after the opening quote. Supports \" \\ \n \t escapes.
local function scan_quoted_string(source, i, len)
  local j = i + 1
  local parts = {}
  while j <= len do
    local c = source:sub(j, j)
    if c == '"' then
      return table.concat(parts), j + 1
    elseif c == "\\" then
      local nc = source:sub(j + 1, j + 1)
      if nc == "n" then parts[#parts + 1] = "\n"
      elseif nc == "t" then parts[#parts + 1] = "\t"
      elseif nc == '"' or nc == "\\" then parts[#parts + 1] = nc
      else parts[#parts + 1] = nc end
      j = j + 2
    else
      parts[#parts + 1] = c
      j = j + 1
    end
  end
  error("unterminated string starting at " .. i)
end

local function tokenize(source)
  local tokens = {}
  local len = #source
  local i = 1
  while i <= len do
    local c = source:sub(i, i)
    if c:match("%s") then
      i = i + 1
    elseif c == "(" then
      local depth = 1
      i = i + 1
      while i <= len and depth > 0 do
        local cc = source:sub(i, i)
        if cc == "(" then depth = depth + 1
        elseif cc == ")" then depth = depth - 1 end
        i = i + 1
      end
    elseif c == "\\" then
      -- Always its own single-character token (works whether or not a
      -- space follows, e.g. \foo and \ foo both split into "\" + "foo")
      -- -- real Factor's word-reference marker, confirmed against its
      -- own docs. This RETIRES the earlier Forth-style "\ line comment"
      -- convention outright, not alongside it: real Factor has no such
      -- use for "\" at all, and the two meanings genuinely collide on
      -- the same character. ( ... ) remains the only comment syntax.
      tokens[#tokens + 1] = { kind = "word", text = "\\" }
      i = i + 1
    elseif c == '"' then
      local text, next_i = scan_quoted_string(source, i, len)
      tokens[#tokens + 1] = { kind = "str", text = text }
      i = next_i
    elseif c == "#" and source:sub(i + 1, i + 1) == ":" then
      -- #: ... ; -- musics-notation sugar for "..." parse-notation,
      -- reusing this SAME depth-tracked raw-character-scan technique the
      -- ( comment scanner above already uses, rather than persistent
      -- mutable state (a Forth-STATE-style toggle was considered and
      -- rejected -- see notation.mforth's own plan/memory note on this --
      -- a global flag left set after a mid-span error is exactly the
      -- footgun this local, self-contained scan avoids).
      --
      -- musics.ebnf's own bracket set ([ ] { } ( ) '[) is depth-tracked
      -- (not just scanned for the first bare ;) so a ; that's really
      -- musics-level content stays inside its own element instead of
      -- closing the span early -- '[ needs no special-casing: its own [
      -- still increments the same single depth counter the ( comment
      -- scanner already uses (one counter, not per-bracket-type
      -- matching), and the leading ' is just inert captured text.
      --
      -- Two spans get skipped WHOLESALE, brackets and all, rather than
      -- depth-tracked: a "..." StringLit (musics.ebnf's own StringLit
      -- has no escape handling, so this is a plain scan-to-the-next-",
      -- same as scan_quoted_string above but keeping the quotes as part
      -- of the captured text) and a %{ ... %} block comment
      -- (musics.ebnf's own Comment rule, #'%\{[\s\S]*?%\}' -- non-greedy,
      -- so the FIRST %} closes it, no nesting). Checked directly against
      -- musics.ebnf rather than assumed: earlier drafting here mistakenly
      -- reasoned about a ;-to-end-of-line comment syntax that doesn't
      -- actually exist in this grammar at all (it's the %{ %} block form
      -- above) -- the real collision risk this guards against is a
      -- semicolon or an unbalanced bracket inside a StringLit or a block
      -- comment's own free-form text (e.g. `#: !title:"A; B" c4 ;`),
      -- confirmed by testing it unguarded first and watching it break.
      local j = i + 2
      local start = j
      local depth = 0
      local closed = false
      while j <= len do
        local cc = source:sub(j, j)
        if cc == '"' then
          j = j + 1
          while j <= len and source:sub(j, j) ~= '"' do j = j + 1 end
          j = j + 1
        elseif cc == "%" and source:sub(j + 1, j + 1) == "{" then
          local close_at = source:find("%}", j + 2, true)
          j = close_at and (close_at + 2) or (len + 1)
        elseif cc == "[" or cc == "{" or cc == "(" then
          depth = depth + 1
          j = j + 1
        elseif cc == "]" or cc == "}" or cc == ")" then
          depth = depth - 1
          j = j + 1
        elseif cc == ";" and depth == 0 then
          closed = true
          break
        else
          j = j + 1
        end
      end
      if not closed then error("unterminated #: ... ; block starting at " .. i) end
      -- Emitted as an ordinary string-literal token followed by an
      -- ordinary word token -- #: is sugar, not a second code path:
      -- parse-notation resolves exactly like any other identifier typed
      -- at this call site, which means USING: notation has to be in
      -- scope here, same as if parse-notation had been hand-typed.
      tokens[#tokens + 1] = { kind = "str", text = source:sub(start, j - 1) }
      tokens[#tokens + 1] = { kind = "word", text = "parse-notation" }
      i = j + 1
    else
      local j = i
      while j <= len and not source:sub(j, j):match("%s") do j = j + 1 end
      tokens[#tokens + 1] = { kind = "word", text = source:sub(i, j - 1) }
      i = j
    end
  end
  return tokens
end

-- ── Reader / compiler ──────────────────────────────────────

local function resolve(text)
  local fn = lookup(text)
  if fn then return fn end
  local n = parse_number(text)
  if n then return function(s) push(s, n) end end
  return nil
end

local function run(steps, s, ctx)
  for _, step in ipairs(steps) do step(s, ctx) end
end
run_ref = run -- so wire_generic's dispatcher (defined above run) can call it

-- { a b c - d e -- comment } -- incoming locals (before "-") bind from
-- the stack at call time, deepest first, read-only, by bare name; extra
-- locals (after "-", before "--") start unbound, touched only via
-- name @ / name !. Kept from the prior Forth-flavored kernel phase at
-- explicit user request even though idiomatic Factor mostly avoids named
-- locals in favor of stack shuffling and combinators.
local function parse_locals_block(tokens, i)
  i = i + 1
  local incoming, extra = {}, {}
  local mode = "incoming"
  while true do
    local tok = tokens[i]
    if not tok then error("unterminated locals block {") end
    if tok.text == "}" then
      return incoming, extra, i + 1
    elseif tok.text == "-" and mode == "incoming" then
      mode = "extra"
    elseif tok.text == "--" then
      mode = "comment"
    elseif mode == "incoming" then
      incoming[#incoming + 1] = tok.text
    elseif mode == "extra" then
      extra[#extra + 1] = tok.text
    end
    i = i + 1
  end
end

-- Rebuilds readable source text from a token range -- used so a
-- quotation prints back as its own literal syntax (real Factor's own
-- behavior for `.`/`.s`), e.g. `[ 1 + ]`, rather than a raw Lua table
-- address. Not byte-for-byte identical to what was typed (whitespace
-- is normalized to single spaces), but faithful in content.
local function tokens_to_text(tokens, from, to)
  local parts = {}
  for idx = from, to do
    local t = tokens[idx]
    if t.kind == "str" then
      parts[#parts + 1] = '"' .. t.text .. '"'
    else
      parts[#parts + 1] = t.text
    end
  end
  return table.concat(parts, " ")
end

-- name -> Lua function(stack, ctx). `steps` are already-compiled;
-- `disp` is what `.`/`.s` show -- either the reconstructed literal
-- source (for a `[...]` written directly in source) or a generic,
-- honest placeholder (for one built at runtime by curry/compose, which
-- have no source tokens to reconstruct from).
local function make_quotation(steps, disp)
  return setmetatable({ steps = steps }, {
    __mtype = "quotation",
    __tostring = function() return disp end,
  })
end

-- Compiles tokens[i..] into a flat list of steps, stopping (and
-- consuming) the first top-level token whose text is a key of `stops`.
-- Returns (steps, stop_text_or_nil, next_i). Used for `:` bodies, `[...]`
-- quotation bodies (nesting works for free via plain recursion), and
-- M: bodies. `locals` (nil outside a locals-bearing definition) threads
-- through nested quotations too -- a quotation can see its enclosing
-- word's locals, matching how real Factor quotations close over scope.
local function compile_block(tokens, i, stops, locals)
  local steps = {}
  while true do
    local tok = tokens[i]
    if not tok then return steps, nil, i end

    if tok.kind == "str" then
      local text = tok.text
      steps[#steps + 1] = function(s) push(s, text) end
      i = i + 1

    elseif tok.kind == "word" and stops[tok.text] then
      return steps, tok.text, i + 1

    elseif tok.kind == "word" and tok.text == "\\" then
      i = i + 1
      local ref = make_wordref(tokens[i] and tokens[i].text)
      steps[#steps + 1] = function(s) push(s, ref) end
      i = i + 1

    elseif tok.kind == "word" and tok.text == "[" then
      local q_steps, term, next_i = compile_block(tokens, i + 1, { ["]"] = true }, locals)
      if not term then error("[ without matching ]") end
      local body_text = tokens_to_text(tokens, i + 1, next_i - 2)
      local quot = make_quotation(q_steps, body_text == "" and "[ ]" or "[ " .. body_text .. " ]")
      steps[#steps + 1] = function(s) push(s, quot) end
      i = next_i

    elseif tok.kind == "word" and locals and locals.incoming[tok.text] then
      local name = tok.text
      steps[#steps + 1] = function(s, ctx) push(s, ctx.env[name]) end
      i = i + 1

    elseif tok.kind == "word" and locals and locals.extra[tok.text] then
      local name = tok.text
      local nxt = tokens[i + 1]
      if nxt and nxt.text == "@" then
        steps[#steps + 1] = function(s, ctx)
          if ctx.env[name] == nil then error("local '" .. name .. "' read before being set -- use ! first") end
          push(s, ctx.env[name])
        end
        i = i + 2
      elseif nxt and nxt.text == "!" then
        steps[#steps + 1] = function(s, ctx) ctx.env[name] = pop(s) end
        i = i + 2
      else
        error("extra local '" .. name .. "' must be accessed via @ or !")
      end

    else
      local step = resolve(tok.text)
      if not step then error("unknown word during compile: " .. tok.text) end
      steps[#steps + 1] = step
      i = i + 1
    end
  end
end

-- ── Combinators ────────────────────────────────────────────
-- Factor's actual control-flow model: no special syntax at all, just
-- ordinary words consuming quotations as plain stack values. This
-- replaces the earlier compile-time if/then/do/loop/case special forms
-- outright, not alongside them.

local function quot_steps(v)
  if mtype(v) ~= "quotation" then error("expected a quotation") end
  return v.steps
end

def("call", function(s, ctx) run(quot_steps(pop(s)), s, ctx) end)

def("if", function(s, ctx)
  local false_q, true_q, flag_v = pop(s), pop(s), pop(s)
  run(quot_steps(is_true(flag_v) and true_q or false_q), s, ctx)
end)
def("when", function(s, ctx)
  local q, flag_v = pop(s), pop(s)
  if is_true(flag_v) then run(quot_steps(q), s, ctx) end
end)
def("unless", function(s, ctx)
  local q, flag_v = pop(s), pop(s)
  if not is_true(flag_v) then run(quot_steps(q), s, ctx) end
end)

-- dip ( x quot -- x ): run quot with x removed from under it, then
-- restore x on top.
def("dip", function(s, ctx)
  local q = pop(s)
  local x = pop(s)
  run(quot_steps(q), s, ctx)
  push(s, x)
end)
-- keep ( x quot -- x ): run quot on x (consuming it), then push the
-- original x back afterward.
def("keep", function(s, ctx)
  local q = pop(s)
  local x = pop(s)
  push(s, x)
  run(quot_steps(q), s, ctx)
  push(s, x)
end)

-- bi ( x p q -- ): applies p to x, then applies q to the SAME original x.
def("bi", function(s, ctx)
  local q, p, x = pop(s), pop(s), pop(s)
  push(s, x); run(quot_steps(p), s, ctx)
  push(s, x); run(quot_steps(q), s, ctx)
end)
-- tri ( x p q r -- ): p, q, r each applied to the same original x.
def("tri", function(s, ctx)
  local r, q, p, x = pop(s), pop(s), pop(s), pop(s)
  push(s, x); run(quot_steps(p), s, ctx)
  push(s, x); run(quot_steps(q), s, ctx)
  push(s, x); run(quot_steps(r), s, ctx)
end)

-- 2dip/3dip ( ..a x y quot -- ..a x y ) / ( ..a x y z quot -- ..a x y z ):
-- confirmed real Factor kernel words, dip generalized to 2/3 removed items.
def("2dip", function(s, ctx)
  local q = pop(s)
  local y, x = pop(s), pop(s)
  run(quot_steps(q), s, ctx)
  push(s, x); push(s, y)
end)
def("3dip", function(s, ctx)
  local q = pop(s)
  local z, y, x = pop(s), pop(s), pop(s)
  run(quot_steps(q), s, ctx)
  push(s, x); push(s, y); push(s, z)
end)
-- 2keep/3keep ( x y quot -- x y ) / ( x y z quot -- x y z ): confirmed
-- real Factor kernel words, keep generalized to 2/3 kept items.
def("2keep", function(s, ctx)
  local q = pop(s)
  local y, x = pop(s), pop(s)
  push(s, x); push(s, y)
  run(quot_steps(q), s, ctx)
  push(s, x); push(s, y)
end)
def("3keep", function(s, ctx)
  local q = pop(s)
  local z, y, x = pop(s), pop(s), pop(s)
  push(s, x); push(s, y); push(s, z)
  run(quot_steps(q), s, ctx)
  push(s, x); push(s, y); push(s, z)
end)

-- 4dip/4keep ( w x y z quot -- w x y z ) / ( ..a w x y z quot -- ..b w x y z ):
-- confirmed real Factor kernel words, dip/keep generalized to 4.
def("4dip", function(s, ctx)
  local q = pop(s)
  local z, y, x, w = pop(s), pop(s), pop(s), pop(s)
  run(quot_steps(q), s, ctx)
  push(s, w); push(s, x); push(s, y); push(s, z)
end)
def("4keep", function(s, ctx)
  local q = pop(s)
  local z, y, x, w = pop(s), pop(s), pop(s), pop(s)
  push(s, w); push(s, x); push(s, y); push(s, z)
  run(quot_steps(q), s, ctx)
  push(s, w); push(s, x); push(s, y); push(s, z)
end)

-- keepd/keepdd/2keepd ( ..a x y quot -- ..b x ) / ( ..a x y z quot -- ..b x )
-- / ( ..a x y z quot -- ..b x y ): confirmed real Factor -- keep, but
-- restoring only the DEEPER kept item(s), the shallower one(s) get
-- consumed by quot along with ..a and never come back.
def("keepd", function(s, ctx)
  local q = pop(s)
  local x = s[#s - 1]
  run(quot_steps(q), s, ctx)
  push(s, x)
end)
def("keepdd", function(s, ctx)
  local q = pop(s)
  local x = s[#s - 2]
  run(quot_steps(q), s, ctx)
  push(s, x)
end)
def("2keepd", function(s, ctx)
  local q = pop(s)
  local x, y = s[#s - 2], s[#s - 1]
  run(quot_steps(q), s, ctx)
  push(s, x); push(s, y)
end)

-- 1check/2check/3check ( ..a x quot: ( ..a x -- ..b ? ) -- ..b x ? ) and
-- friends: confirmed real Factor defs (`keep swap` / `2keep rot` /
-- `3keep roll`) -- quot consumes the kept item(s) as part of its own
-- normal effect (unlike 1if's pred below, which is wrapped to do this
-- FOR the caller); these just restore them after, with the flag moved
-- to the very top.
def("1check", function(s, ctx)
  local quot = pop(s)
  local x = s[#s]
  run(quot_steps(quot), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, flag)
end)
def("2check", function(s, ctx)
  local quot = pop(s)
  local x, y = s[#s - 1], s[#s]
  run(quot_steps(quot), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y); push(s, flag)
end)
def("3check", function(s, ctx)
  local quot = pop(s)
  local x, y, z = s[#s - 2], s[#s - 1], s[#s]
  run(quot_steps(quot), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y); push(s, z); push(s, flag)
end)

-- 1guard/2guard/3guard: confirmed real Factor defs (`1check [ drop f ]
-- unless` and friends) -- like *check, but collapses to f (ALL of the
-- kept items at once, not independently) instead of leaving a separate
-- flag when the test fails.
def("1guard", function(s, ctx)
  local quot = pop(s)
  local x = s[#s]
  run(quot_steps(quot), s, ctx)
  local flag = pop(s)
  push(s, is_true(flag) and x or F)
end)
def("2guard", function(s, ctx)
  local quot = pop(s)
  local x, y = s[#s - 1], s[#s]
  run(quot_steps(quot), s, ctx)
  local flag = pop(s)
  if is_true(flag) then push(s, x); push(s, y) else push(s, F); push(s, F) end
end)
def("3guard", function(s, ctx)
  local quot = pop(s)
  local x, y, z = s[#s - 2], s[#s - 1], s[#s]
  run(quot_steps(quot), s, ctx)
  local flag = pop(s)
  if is_true(flag) then push(s, x); push(s, y); push(s, z)
  else push(s, F); push(s, F); push(s, F) end
end)

-- 1if/2if/3if: confirmed real Factor defs (`[ 1check ] 2dip if` and
-- friends) -- pred's OWN real effect consumes the kept item(s) just
-- like *check's quot does (this kernel wraps that restoring step here,
-- matching what `[ 1check ] 2dip` does in the real definition), THEN
-- branches between true/false with the item(s) restored either way.
def("1if", function(s, ctx)
  local false_q, true_q, pred = pop(s), pop(s), pop(s)
  local x = s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x)
  run(quot_steps(is_true(flag) and true_q or false_q), s, ctx)
end)
def("2if", function(s, ctx)
  local false_q, true_q, pred = pop(s), pop(s), pop(s)
  local x, y = s[#s - 1], s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y)
  run(quot_steps(is_true(flag) and true_q or false_q), s, ctx)
end)
def("3if", function(s, ctx)
  local false_q, true_q, pred = pop(s), pop(s), pop(s)
  local x, y, z = s[#s - 2], s[#s - 1], s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y); push(s, z)
  run(quot_steps(is_true(flag) and true_q or false_q), s, ctx)
end)

-- 1when/1unless/2when/2unless/3when/3unless: confirmed real Factor
-- defs (`[ ] 1if` / `[ ] swap 1if` and friends) -- 1if/2if/3if with one
-- branch fixed to a no-op.
def("1when", function(s, ctx)
  local true_q, pred = pop(s), pop(s)
  local x = s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x)
  if is_true(flag) then run(quot_steps(true_q), s, ctx) end
end)
def("1unless", function(s, ctx)
  local false_q, pred = pop(s), pop(s)
  local x = s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x)
  if not is_true(flag) then run(quot_steps(false_q), s, ctx) end
end)
def("2when", function(s, ctx)
  local true_q, pred = pop(s), pop(s)
  local x, y = s[#s - 1], s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y)
  if is_true(flag) then run(quot_steps(true_q), s, ctx) end
end)
def("2unless", function(s, ctx)
  local false_q, pred = pop(s), pop(s)
  local x, y = s[#s - 1], s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y)
  if not is_true(flag) then run(quot_steps(false_q), s, ctx) end
end)
def("3when", function(s, ctx)
  local true_q, pred = pop(s), pop(s)
  local x, y, z = s[#s - 2], s[#s - 1], s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y); push(s, z)
  if is_true(flag) then run(quot_steps(true_q), s, ctx) end
end)
def("3unless", function(s, ctx)
  local false_q, pred = pop(s), pop(s)
  local x, y, z = s[#s - 2], s[#s - 1], s[#s]
  run(quot_steps(pred), s, ctx)
  local flag = pop(s)
  push(s, x); push(s, y); push(s, z)
  if not is_true(flag) then run(quot_steps(false_q), s, ctx) end
end)

-- if*/when*/unless* ( ..a ? true/false... -- ..b ): confirmed real
-- Factor defs -- "anaphoric" siblings of if/when/unless: the true
-- branch also receives the flag itself (not just the rest of the
-- stack), useful when the flag carries real information beyond t/f
-- (this kernel's `f` doubles as "missing", so a value that's merely
-- non-f is common here).
def("if*", function(s, ctx)
  local false_q, true_q = pop(s), pop(s)
  local flag = pop(s)
  if is_true(flag) then push(s, flag); run(quot_steps(true_q), s, ctx)
  else run(quot_steps(false_q), s, ctx) end
end)
def("when*", function(s, ctx)
  local true_q = pop(s)
  local flag = s[#s]
  if is_true(flag) then run(quot_steps(true_q), s, ctx) else pop(s) end
end)
def("unless*", function(s, ctx)
  local false_q = pop(s)
  local flag = pop(s)
  if is_true(flag) then push(s, flag) else run(quot_steps(false_q), s, ctx) end
end)

-- ?call ( ..a obj/f quot: ( ..a obj -- ..a obj' ) -- ..a obj'/f ):
-- confirmed real Factor def, `dupd when` -- runs quot on obj only if
-- obj isn't f, otherwise leaves f as-is.
def("?call", function(s, ctx)
  local quot, obj = pop(s), pop(s)
  push(s, obj)
  if is_true(obj) then run(quot_steps(quot), s, ctx) end
end)

-- ?transmute/transmute ( old quot: ( old -- new/f ) -- new/old new?/-- ):
-- confirmed real Factor defs (`keep swap or?` / `?transmute drop`) --
-- runs quot on old; if it produced a real (non-f) new value, that's
-- the result (and new?=t), otherwise old is left unchanged (new?=f).
def("?transmute", function(s, ctx)
  local quot, old = pop(s), pop(s)
  push(s, old)
  run(quot_steps(quot), s, ctx)
  local new_or_f = pop(s)
  if is_true(new_or_f) then push(s, new_or_f); push(s, T)
  else push(s, old); push(s, F) end
end)
def("transmute", function(s, ctx)
  local quot, old = pop(s), pop(s)
  push(s, old)
  run(quot_steps(quot), s, ctx)
  local new_or_f = pop(s)
  push(s, is_true(new_or_f) and new_or_f or old)
end)

-- ?if/?when/?unless: confirmed real Factor defs (`[ ?transmute ] 2dip
-- if` / `[ ?transmute ] dip when` / `[ ?transmute ] dip unless`) --
-- cond tries to produce a replacement for default; true/false then
-- branch on whether it succeeded.
def("?if", function(s, ctx)
  local false_q, true_q, cond, default = pop(s), pop(s), pop(s), pop(s)
  push(s, default)
  run(quot_steps(cond), s, ctx)
  local new_or_f = pop(s)
  if is_true(new_or_f) then push(s, new_or_f); run(quot_steps(true_q), s, ctx)
  else push(s, default); run(quot_steps(false_q), s, ctx) end
end)
def("?when", function(s, ctx)
  local true_q, cond, default = pop(s), pop(s), pop(s)
  push(s, default)
  run(quot_steps(cond), s, ctx)
  local new_or_f = pop(s)
  if is_true(new_or_f) then push(s, new_or_f); run(quot_steps(true_q), s, ctx)
  else push(s, default) end
end)
def("?unless", function(s, ctx)
  local false_q, cond, default = pop(s), pop(s), pop(s)
  push(s, default)
  run(quot_steps(cond), s, ctx)
  local new_or_f = pop(s)
  if is_true(new_or_f) then push(s, new_or_f)
  else push(s, default); run(quot_steps(false_q), s, ctx) end
end)

-- negate/same?/prepose/do: confirmed real Factor defs.
-- negate ( quot -- quot' ): a new quotation running quot then `not`.
def("negate", function(s)
  local quot = pop(s)
  local composed = {}
  for _, step in ipairs(quot_steps(quot)) do composed[#composed + 1] = step end
  composed[#composed + 1] = function(s2) push(s2, bool(not is_true(pop(s2)))) end
  push(s, make_quotation(composed, "[ ... ]"))
end)
-- same? ( x y quot -- ? ): quot applied to x and y (bi@-style), then
-- compares the two results with =.
def("same?", function(s, ctx)
  local quot, y, x = pop(s), pop(s), pop(s)
  local steps = quot_steps(quot)
  push(s, x); run(steps, s, ctx)
  push(s, y); run(steps, s, ctx)
  local rb, ra = pop(s), pop(s)
  push(s, bool(values_eq(ra, rb)))
end)
-- prepose ( quot1 quot2 -- composed ): compose with the arguments
-- swapped -- the result runs quot2 then quot1 (`swap compose`).
def("prepose", function(s)
  local quot2, quot1 = pop(s), pop(s)
  local composed = {}
  for _, step in ipairs(quot_steps(quot2)) do composed[#composed + 1] = step end
  for _, step in ipairs(quot_steps(quot1)) do composed[#composed + 1] = step end
  push(s, make_quotation(composed, "[ ... ]"))
end)
-- do ( pred body -- pred body ): runs body once (as a side effect on
-- the surrounding stack), then leaves pred/body unchanged -- used by
-- while's own real definition to run the loop body before its first
-- pred check.
def("do", function(s, ctx)
  local body, pred = pop(s), pop(s)
  run(quot_steps(body), s, ctx)
  push(s, pred); push(s, body)
end)

-- while*/until*: confirmed real Factor defs -- while/until's
-- "anaphoric" siblings, where body also sees the flag pred left (and,
-- for until*, the flag stays on the stack at the very end too).
def("while*", function(s, ctx)
  local body, pred = pop(s), pop(s)
  local pred_steps, body_steps = quot_steps(pred), quot_steps(body)
  while true do
    run(pred_steps, s, ctx)
    local flag = s[#s]
    if not is_true(flag) then pop(s); break end
    run(body_steps, s, ctx)
  end
end)
def("until*", function(s, ctx)
  local body, pred = pop(s), pop(s)
  local pred_steps, body_steps = quot_steps(pred), quot_steps(body)
  while true do
    run(pred_steps, s, ctx)
    local flag = s[#s]
    if is_true(flag) then break end
    pop(s)
    run(body_steps, s, ctx)
  end
end)

-- 2bi/3bi ( x y p q -- ) / ( x y z p q -- ): confirmed real Factor kernel
-- words, bi generalized to 2/3 shared values.
def("2bi", function(s, ctx)
  local q, p, y, x = pop(s), pop(s), pop(s), pop(s)
  push(s, x); push(s, y); run(quot_steps(p), s, ctx)
  push(s, x); push(s, y); run(quot_steps(q), s, ctx)
end)
def("3bi", function(s, ctx)
  local q, p, z, y, x = pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, x); push(s, y); push(s, z); run(quot_steps(p), s, ctx)
  push(s, x); push(s, y); push(s, z); run(quot_steps(q), s, ctx)
end)
-- 2tri/3tri ( x y p q r -- ) / ( x y z p q r -- ): confirmed real Factor
-- kernel words, tri generalized to 2/3 shared values.
def("2tri", function(s, ctx)
  local r, q, p, y, x = pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, x); push(s, y); run(quot_steps(p), s, ctx)
  push(s, x); push(s, y); run(quot_steps(q), s, ctx)
  push(s, x); push(s, y); run(quot_steps(r), s, ctx)
end)
def("3tri", function(s, ctx)
  local r, q, p, z, y, x = pop(s), pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, x); push(s, y); push(s, z); run(quot_steps(p), s, ctx)
  push(s, x); push(s, y); push(s, z); run(quot_steps(q), s, ctx)
  push(s, x); push(s, y); push(s, z); run(quot_steps(r), s, ctx)
end)

-- bi*/tri* ( x y p q -- ) / ( x y z p q r -- ): confirmed real Factor
-- kernel words -- p applied to x, q applied to y (and r to z), NOT the
-- same value to each like bi*/tri's own sibling above.
def("bi*", function(s, ctx)
  local q, p, y, x = pop(s), pop(s), pop(s), pop(s)
  push(s, x); run(quot_steps(p), s, ctx)
  push(s, y); run(quot_steps(q), s, ctx)
end)
def("tri*", function(s, ctx)
  local r, q, p, z, y, x = pop(s), pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, x); run(quot_steps(p), s, ctx)
  push(s, y); run(quot_steps(q), s, ctx)
  push(s, z); run(quot_steps(r), s, ctx)
end)
-- bi@/tri@ ( x y quot -- ) / ( x y z quot -- ): confirmed real Factor
-- kernel words -- the SAME quot applied to each value in turn.
def("bi@", function(s, ctx)
  local q, y, x = pop(s), pop(s), pop(s)
  local steps = quot_steps(q)
  push(s, x); run(steps, s, ctx)
  push(s, y); run(steps, s, ctx)
end)
def("tri@", function(s, ctx)
  local q, z, y, x = pop(s), pop(s), pop(s), pop(s)
  local steps = quot_steps(q)
  push(s, x); run(steps, s, ctx)
  push(s, y); run(steps, s, ctx)
  push(s, z); run(steps, s, ctx)
end)

-- 2bi*/2tri* ( w x y z p q -- ) / ( u v w x y z p q r -- ): confirmed
-- real Factor kernel words -- bi*/tri* generalized to PAIRS of shared
-- values instead of single ones (p applied to (w,x), q to (y,z), ...).
def("2bi*", function(s, ctx)
  local q, p, z, y, x, w = pop(s), pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, w); push(s, x); run(quot_steps(p), s, ctx)
  push(s, y); push(s, z); run(quot_steps(q), s, ctx)
end)
def("2tri*", function(s, ctx)
  local r, q, p, z, y, x, w, v, u =
    pop(s), pop(s), pop(s), pop(s), pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, u); push(s, v); run(quot_steps(p), s, ctx)
  push(s, w); push(s, x); run(quot_steps(q), s, ctx)
  push(s, y); push(s, z); run(quot_steps(r), s, ctx)
end)
-- 2bi@/2tri@ ( w x y z quot -- ) / ( u v w x y z quot -- ): confirmed
-- real Factor kernel words -- bi@/tri@ generalized to pairs, the SAME
-- quot applied to each pair in turn.
def("2bi@", function(s, ctx)
  local q, z, y, x, w = pop(s), pop(s), pop(s), pop(s), pop(s)
  local steps = quot_steps(q)
  push(s, w); push(s, x); run(steps, s, ctx)
  push(s, y); push(s, z); run(steps, s, ctx)
end)
def("2tri@", function(s, ctx)
  local q, z, y, x, w, v, u = pop(s), pop(s), pop(s), pop(s), pop(s), pop(s), pop(s)
  local steps = quot_steps(q)
  push(s, u); push(s, v); run(steps, s, ctx)
  push(s, w); push(s, x); run(steps, s, ctx)
  push(s, y); push(s, z); run(steps, s, ctx)
end)

-- both?/either? ( x y quot -- ? ): confirmed real Factor kernel words,
-- `bi@ and` / `bi@ or`.
def("both?", function(s, ctx)
  local q, y, x = pop(s), pop(s), pop(s)
  local steps = quot_steps(q)
  push(s, x); run(steps, s, ctx)
  local r1 = is_true(pop(s))
  push(s, y); run(steps, s, ctx)
  push(s, bool(r1 and is_true(pop(s))))
end)
def("either?", function(s, ctx)
  local q, y, x = pop(s), pop(s), pop(s)
  local steps = quot_steps(q)
  push(s, x); run(steps, s, ctx)
  local r1 = is_true(pop(s))
  push(s, y); run(steps, s, ctx)
  push(s, bool(r1 or is_true(pop(s))))
end)

-- most ( x y quot -- z ): confirmed real Factor kernel word -- quot's
-- own effect is ( x y -- ? ); picks x if true, else y. Real min/max are
-- both defined in terms of this (`[ before? ] most` / `[ after? ]
-- most`), matching the direct math.order implementation above.
def("most", function(s, ctx)
  local q, y, x = pop(s), pop(s), pop(s)
  push(s, x); push(s, y)
  run(quot_steps(q), s, ctx)
  push(s, is_true(pop(s)) and x or y)
end)

-- cleave ( x seq -- ): confirmed real Factor stack effect -- seq holds
-- quotations, each applied in turn to a fresh copy of x. This is bi/tri
-- generalized to N quotations (real Factor's own combinators vocab
-- doesn't generalize this way either -- generalizations.factor's n-ary
-- words are MACRO:-based compile-time expansions this kernel has no
-- equivalent machinery for; cleave covers the same need directly).
def("cleave", function(s, ctx)
  local qseq, x = pop(s), pop(s)
  for idx = 0, seq_len(qseq) - 1 do
    push(s, x)
    run(quot_steps(seq_get(qseq, idx)), s, ctx)
  end
end)

-- ── make (core/make): building sequences with `,`/`%` ──────────────
-- Confirmed against real Factor source/docs (core/make/make.factor) --
-- opens a new builder, runs quot with it as the innermost target of
-- `,`/`%` (see the shared `,` above), and pushes the accumulated
-- result. Scoped to sequence exemplars only -- real Factor's make-assoc
-- half (`,,`/`%%`, an H{ } exemplar) isn't ported, since this kernel
-- has no `H{ }` input syntax to write an exemplar literal with anyway.
-- A Lua-level stack, not real Factor's dynamic-scope `building`
-- variable (this kernel has no namespaces vocab) -- nested make calls
-- still each get their own builder, the same observable behavior.
def("make", function(s, ctx)
  local exemplar, quot = pop(s), pop(s)
  if mtype(exemplar) ~= "seq" then error("make: only sequence exemplars are supported") end
  local builder = tagged("seq")
  make_stack[#make_stack + 1] = builder
  local ok, err = pcall(run, quot_steps(quot), s, ctx)
  make_stack[#make_stack] = nil
  if not ok then error(err, 0) end
  push(s, builder)
end)

-- % ( seq -- ): confirmed real Factor stack effect -- appends every
-- element of seq to the innermost open make builder.
def("%", function(s)
  local builder = make_stack[#make_stack]
  if not builder then error("%: used outside make") end
  local seq = pop(s)
  local n = seq_len(builder)
  for idx = 0, seq_len(seq) - 1 do
    seq_set(builder, n + idx, seq_get(seq, idx))
  end
end)

-- curry ( obj quot -- curry ): a new quotation that pushes obj, then
-- runs the original.
def("curry", function(s)
  local quot, obj = pop(s), pop(s)
  local base = quot_steps(quot)
  local curried = { function(s2) push(s2, obj) end }
  for _, step in ipairs(base) do curried[#curried + 1] = step end
  -- No source tokens to reconstruct from (this quotation is built at
  -- runtime, not read from source) -- an honest generic placeholder
  -- rather than a raw Lua table address.
  push(s, make_quotation(curried, "[ ... ]"))
end)
-- 2curry/3curry ( obj1 obj2 quot -- curried ) / ( obj1 obj2 obj3 quot -- curried ):
-- confirmed real Factor -- curry generalized to pushing 2/3 objects
-- ahead of the original quotation instead of 1.
def("2curry", function(s)
  local quot, obj2, obj1 = pop(s), pop(s), pop(s)
  local base = quot_steps(quot)
  local curried = { function(s2) push(s2, obj1); push(s2, obj2) end }
  for _, step in ipairs(base) do curried[#curried + 1] = step end
  push(s, make_quotation(curried, "[ ... ]"))
end)
def("3curry", function(s)
  local quot, obj3, obj2, obj1 = pop(s), pop(s), pop(s), pop(s)
  local base = quot_steps(quot)
  local curried = { function(s2) push(s2, obj1); push(s2, obj2); push(s2, obj3) end }
  for _, step in ipairs(base) do curried[#curried + 1] = step end
  push(s, make_quotation(curried, "[ ... ]"))
end)

-- with/withd ( param obj quot -- obj curried ): confirmed real Factor
-- defs (`swapd [ swapd call ] 2curry` / `swapd [ -rotd call ] 2curry`),
-- re-derived here to their simpler NET effect rather than transcribed
-- mechanically -- traced by hand against the real definitions and
-- confirmed equivalent (see the commit message for the full trace):
-- `with`'s curried quotation inserts param just below whatever it's
-- called with; `withd`'s inserts it one level deeper still (below the
-- top TWO items at call time, so it needs at least two items present).
-- Used inside combinators like each/map to curry a fixed value into a
-- quotation while still leaving room for the iteration element on top.
def("with", function(s)
  local quot, obj, param = pop(s), pop(s), pop(s)
  local steps = {
    function(s2)
      local x = pop(s2)
      push(s2, param)
      push(s2, x)
    end,
  }
  for _, step in ipairs(quot_steps(quot)) do steps[#steps + 1] = step end
  push(s, obj)
  push(s, make_quotation(steps, "[ ... ]"))
end)
def("withd", function(s)
  local quot, obj, param = pop(s), pop(s), pop(s)
  local steps = {
    function(s2)
      local x, w = pop(s2), pop(s2)
      push(s2, param)
      push(s2, w)
      push(s2, x)
    end,
  }
  for _, step in ipairs(quot_steps(quot)) do steps[#steps + 1] = step end
  push(s, obj)
  push(s, make_quotation(steps, "[ ... ]"))
end)
-- 2with ( param1 param2 obj quot -- obj curried ): confirmed real
-- Factor def, `with with` -- both params inserted below the call-time
-- argument, param1 deepest.
def("2with", function(s)
  local quot, obj, param2, param1 = pop(s), pop(s), pop(s), pop(s)
  local steps = {
    function(s2)
      local x = pop(s2)
      push(s2, param1)
      push(s2, param2)
      push(s2, x)
    end,
  }
  for _, step in ipairs(quot_steps(quot)) do steps[#steps + 1] = step end
  push(s, obj)
  push(s, make_quotation(steps, "[ ... ]"))
end)

-- bi-curry/tri-curry family ( x p q -- p' q' ) / etc: confirmed real
-- Factor defs (all built from a shared private `currier` helper);
-- re-derived to their net effect -- curry the same object(s) into each
-- of the given quotations independently, returning the curried
-- quotations instead of calling them.
local function make_curry(obj, quot)
  local base = quot_steps(quot)
  local curried = { function(s2) push(s2, obj) end }
  for _, step in ipairs(base) do curried[#curried + 1] = step end
  return make_quotation(curried, "[ ... ]")
end
-- bi-curry/tri-curry: the SAME x curried into each quotation.
def("bi-curry", function(s)
  local q, p, x = pop(s), pop(s), pop(s)
  push(s, make_curry(x, p)); push(s, make_curry(x, q))
end)
def("tri-curry", function(s)
  local r, q, p, x = pop(s), pop(s), pop(s), pop(s)
  push(s, make_curry(x, p)); push(s, make_curry(x, q)); push(s, make_curry(x, r))
end)
-- bi-curry*/tri-curry*: a DIFFERENT object curried into each quotation.
def("bi-curry*", function(s)
  local q, p, y, x = pop(s), pop(s), pop(s), pop(s)
  push(s, make_curry(x, p)); push(s, make_curry(y, q))
end)
def("tri-curry*", function(s)
  local r, q, p, z, y, x = pop(s), pop(s), pop(s), pop(s), pop(s), pop(s)
  push(s, make_curry(x, p)); push(s, make_curry(y, q)); push(s, make_curry(z, r))
end)
-- bi-curry@/tri-curry@: the SAME quotation curried with each object.
def("bi-curry@", function(s)
  local q, y, x = pop(s), pop(s), pop(s)
  push(s, make_curry(x, q)); push(s, make_curry(y, q))
end)
def("tri-curry@", function(s)
  local q, z, y, x = pop(s), pop(s), pop(s), pop(s)
  push(s, make_curry(x, q)); push(s, make_curry(y, q)); push(s, make_curry(z, q))
end)

-- compose ( quot1 quot2 -- q1q2 ): a new quotation running quot1 then quot2.
def("compose", function(s)
  local quot2, quot1 = pop(s), pop(s)
  local composed = {}
  for _, step in ipairs(quot_steps(quot1)) do composed[#composed + 1] = step end
  for _, step in ipairs(quot_steps(quot2)) do composed[#composed + 1] = step end
  push(s, make_quotation(composed, "[ ... ]"))
end)

-- each ( seq quot -- ): calls quot with each element of seq, in order.
def("each", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  for idx = 0, seq_len(seq) - 1 do
    push(s, seq_get(seq, idx))
    run(steps, s, ctx)
  end
end)
-- map ( seq quot -- newseq ): like each, collecting quot's results.
def("map", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  local result = tagged("seq")
  for idx = 0, seq_len(seq) - 1 do
    push(s, seq_get(seq, idx))
    run(steps, s, ctx)
    result[make_rational(idx, 1)] = pop(s)
  end
  push(s, result)
end)
-- reduce ( seq identity quot -- result ): folds quot over seq, left to
-- right, starting from identity. quot's own effect is ( acc elem -- acc ).
def("reduce", function(s, ctx)
  local quot, identity, seq = pop(s), pop(s), pop(s)
  local steps = quot_steps(quot)
  local acc = identity
  for idx = 0, seq_len(seq) - 1 do
    push(s, acc)
    push(s, seq_get(seq, idx))
    run(steps, s, ctx)
    acc = pop(s)
  end
  push(s, acc)
end)
-- filter ( seq quot -- subseq ): confirmed real Factor stack effect --
-- same seq-then-quot argument order as each/map/reduce above. quot's own
-- effect is ( elt -- ? ); keeps the elements it leaves true for.
def("filter", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  local result = tagged("seq")
  local j = 0
  for idx = 0, seq_len(seq) - 1 do
    local elt = seq_get(seq, idx)
    push(s, elt)
    run(steps, s, ctx)
    if is_true(pop(s)) then
      result[make_rational(j, 1)] = elt
      j = j + 1
    end
  end
  push(s, result)
end)
-- reject ( seq quot -- subseq ): confirmed real Factor word, filter's
-- polarity-inverse (same relationship as when/unless and while/until
-- elsewhere in this kernel) -- keeps the elements quot leaves FALSE for.
def("reject", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  local result = tagged("seq")
  local j = 0
  for idx = 0, seq_len(seq) - 1 do
    local elt = seq_get(seq, idx)
    push(s, elt)
    run(steps, s, ctx)
    if not is_true(pop(s)) then
      result[make_rational(j, 1)] = elt
      j = j + 1
    end
  end
  push(s, result)
end)
-- sum/product ( seq -- n ): confirmed real Factor stack effects --
-- reduce with +/*, identity 0/1.
def("sum", function(s)
  local seq = pop(s)
  local acc = make_rational(0, 1)
  for idx = 0, seq_len(seq) - 1 do acc = num_add(acc, seq_get(seq, idx)) end
  push(s, acc)
end)
def("product", function(s)
  local seq = pop(s)
  local acc = make_rational(1, 1)
  for idx = 0, seq_len(seq) - 1 do acc = num_mul(acc, seq_get(seq, idx)) end
  push(s, acc)
end)

-- 2each/2map ( seq1 seq2 quot -- ) / ( seq1 seq2 quot -- newseq ):
-- confirmed real Factor stack effects -- parallel iteration over two
-- same-length sequences.
def("2each", function(s, ctx)
  local quot, seq2, seq1 = pop(s), pop(s), pop(s)
  local steps = quot_steps(quot)
  for idx = 0, seq_len(seq1) - 1 do
    push(s, seq_get(seq1, idx))
    push(s, seq_get(seq2, idx))
    run(steps, s, ctx)
  end
end)
def("2map", function(s, ctx)
  local quot, seq2, seq1 = pop(s), pop(s), pop(s)
  local steps = quot_steps(quot)
  local result = tagged("seq")
  for idx = 0, seq_len(seq1) - 1 do
    push(s, seq_get(seq1, idx))
    push(s, seq_get(seq2, idx))
    run(steps, s, ctx)
    result[make_rational(idx, 1)] = pop(s)
  end
  push(s, result)
end)

-- any?/all?/count ( seq quot -- ? / ? / n ): confirmed real Factor
-- stack effects.
def("any?", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  for idx = 0, seq_len(seq) - 1 do
    push(s, seq_get(seq, idx))
    run(steps, s, ctx)
    if is_true(pop(s)) then push(s, T); return end
  end
  push(s, F)
end)
def("all?", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  for idx = 0, seq_len(seq) - 1 do
    push(s, seq_get(seq, idx))
    run(steps, s, ctx)
    if not is_true(pop(s)) then push(s, F); return end
  end
  push(s, T)
end)
def("count", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  local n = 0
  for idx = 0, seq_len(seq) - 1 do
    push(s, seq_get(seq, idx))
    run(steps, s, ctx)
    if is_true(pop(s)) then n = n + 1 end
  end
  push(s, make_rational(n, 1))
end)

-- member?/remove ( elt seq -- ? / newseq ): confirmed real Factor stack
-- effects and defs (`[ = ] with any?` / `[ = ] with reject`).
def("member?", function(s)
  local seq, elt = pop(s), pop(s)
  for idx = 0, seq_len(seq) - 1 do
    if values_eq(seq_get(seq, idx), elt) then push(s, T); return end
  end
  push(s, F)
end)
def("remove", function(s)
  local seq, elt = pop(s), pop(s)
  local result = tagged("seq")
  local j = 0
  for idx = 0, seq_len(seq) - 1 do
    local v = seq_get(seq, idx)
    if not values_eq(v, elt) then result[make_rational(j, 1)] = v; j = j + 1 end
  end
  push(s, result)
end)

-- minimum/maximum ( seq -- elt ): confirmed real Factor stack effects.
def("minimum", function(s)
  local seq = pop(s)
  local n = seq_len(seq)
  local best = seq_get(seq, 0)
  for idx = 1, n - 1 do
    local v = seq_get(seq, idx)
    if compare3(v, best) == LT then best = v end
  end
  push(s, best)
end)
def("maximum", function(s)
  local seq = pop(s)
  local n = seq_len(seq)
  local best = seq_get(seq, 0)
  for idx = 1, n - 1 do
    local v = seq_get(seq, idx)
    if compare3(v, best) == GT then best = v end
  end
  push(s, best)
end)

-- concat ( seq -- newseq ): confirmed real Factor stack effect --
-- flattens a sequence of sequences one level.
def("concat", function(s)
  local seq = pop(s)
  local result = tagged("seq")
  local j = 0
  for idx = 0, seq_len(seq) - 1 do
    local inner = seq_get(seq, idx)
    for k = 0, seq_len(inner) - 1 do
      result[make_rational(j, 1)] = seq_get(inner, k)
      j = j + 1
    end
  end
  push(s, result)
end)

-- sort-with ( seq quot -- sortedseq ): confirmed real Factor word --
-- sorts using quot's own three-way result instead of natural <=>.
-- quot's effect is ( obj1 obj2 -- <=> ).
def("sort-with", function(s, ctx)
  local quot, seq = pop(s), pop(s)
  local steps = quot_steps(quot)
  local arr = {}
  for idx = 0, seq_len(seq) - 1 do arr[#arr + 1] = seq_get(seq, idx) end
  table.sort(arr, function(a, b)
    push(s, a); push(s, b)
    run(steps, s, ctx)
    return pop(s) == LT
  end)
  local result = tagged("seq")
  for idx, v in ipairs(arr) do result[make_rational(idx - 1, 1)] = v end
  push(s, result)
end)

-- times ( n quot -- ): calls quot n times, with no input.
def("times", function(s, ctx)
  local quot, n = pop(s), pop(s)
  local steps = quot_steps(quot)
  for _ = 1, to_float(n) do run(steps, s, ctx) end
end)
-- while ( pred body -- ): runs pred (leaving a flag on top), loops
-- running body then pred again while that flag is true.
def("while", function(s, ctx)
  local body, pred = pop(s), pop(s)
  local pred_steps, body_steps = quot_steps(pred), quot_steps(body)
  while true do
    run(pred_steps, s, ctx)
    if not is_true(pop(s)) then break end
    run(body_steps, s, ctx)
  end
end)
-- until ( pred body -- ): confirmed real Factor combinator, while's
-- inverse-polarity sibling -- loops while the flag stays FALSE,
-- stopping the moment it becomes true (while stops the moment it goes
-- false).
def("until", function(s, ctx)
  local body, pred = pop(s), pop(s)
  local pred_steps, body_steps = quot_steps(pred), quot_steps(body)
  while true do
    run(pred_steps, s, ctx)
    if is_true(pop(s)) then break end
    run(body_steps, s, ctx)
  end
end)
-- loop ( pred -- ): confirmed real Factor combinator -- ONE quotation,
-- not two, serving as both test and body; keeps calling it until it
-- leaves f on top.
def("loop", function(s, ctx)
  local steps = quot_steps(pop(s))
  while true do
    run(steps, s, ctx)
    if not is_true(pop(s)) then break end
  end
end)

-- <iota> ( n -- iota ): confirmed real Factor name (angle-bracket
-- constructor, matching <vector>/<hashtable>/<point> etc. -- the
-- pasted reference table's bare "iota" wasn't quite right). Real
-- Factor's version is a lazy, memory-efficient virtual sequence; this
-- materializes a concrete 0..n-1 vector instead -- a reasonable
-- simplification, this kernel has no lazy-sequence machinery.
def("<iota>", function(s)
  local count = to_float(pop(s))
  local seq = tagged("seq")
  for i = 0, count - 1 do
    local ri = make_rational(i, 1)
    seq[ri] = ri
  end
  push(s, seq)
end)

-- ── math.combinatorics ─────────────────────────────────────────────
-- factorial/nPk/nCk re-derived directly against real Factor's stack
-- effects and semantics rather than transcribing the range-based source
-- verbatim (this kernel has no <iota>-style inclusive integer range
-- machinery to port that literally against) -- same "honest adaptation,
-- not a claimed exact match" standard sqrt above already uses. No
-- bignum overflow protection, same limitation noted elsewhere in this
-- file for large results.
local function factorial_of(n)
  local result = make_rational(1, 1)
  for i = 2, n do result = num_mul(result, make_rational(i, 1)) end
  return result
end
def("factorial", function(s) push(s, factorial_of(to_float(pop(s)))) end)

-- nPk ( n k -- nPk ): confirmed real Factor stack effect -- count of
-- k-permutations of n, 0 when k > n or k < 0.
def("nPk", function(s)
  local k, n = to_float(pop(s)), to_float(pop(s))
  if k < 0 or k > n then push(s, make_rational(0, 1)); return end
  local result = make_rational(1, 1)
  for i = n - k + 1, n do result = num_mul(result, make_rational(i, 1)) end
  push(s, result)
end)

-- nCk ( n k -- nCk ): confirmed real Factor stack effect -- binomial coefficient.
def("nCk", function(s)
  local k, n = to_float(pop(s)), to_float(pop(s))
  if k < 0 or k > n then push(s, make_rational(0, 1)); return end
  push(s, num_div(factorial_of(n), num_mul(factorial_of(k), factorial_of(n - k))))
end)

-- all-permutations ( seq -- seq' ): confirmed real Factor stack effect
-- and semantics (every permutation of seq); iterative Heap's algorithm
-- here rather than real Factor's own factoradic-index method -- same
-- element set, an unspecified-either-way different order, an honest
-- simplification like sqrt's above.
def("all-permutations", function(s)
  local seq = pop(s)
  local n = seq_len(seq)
  local arr = {}
  for idx = 0, n - 1 do arr[idx + 1] = seq_get(seq, idx) end
  local results = tagged("seq")
  local count = 0
  local function emit()
    local perm = tagged("seq")
    for i = 1, n do perm[make_rational(i - 1, 1)] = arr[i] end
    results[make_rational(count, 1)] = perm
    count = count + 1
  end
  emit()
  if n > 0 then
    local c = {}
    for idx = 1, n do c[idx] = 0 end
    local i = 1
    while i <= n do
      if c[i] < i - 1 then
        if i % 2 == 1 then arr[1], arr[i] = arr[i], arr[1]
        else arr[c[i] + 1], arr[i] = arr[i], arr[c[i] + 1] end
        emit()
        c[i] = c[i] + 1
        i = 1
      else
        c[i] = 0
        i = i + 1
      end
    end
  end
  push(s, results)
end)

-- ── math.statistics ────────────────────────────────────────────────
-- mean/population-var/sample-var/*-std confirmed against real Factor
-- stack effects and defs (basis/math/statistics); `std` is real
-- Factor's own ALIAS for sample-std (ddof 1, Bessel's correction), not
-- population-std.
local function mean_of(seq)
  local n = seq_len(seq)
  local total = make_rational(0, 1)
  for idx = 0, n - 1 do total = num_add(total, seq_get(seq, idx)) end
  return num_div(total, make_rational(n, 1))
end
local function var_ddof(seq, ddof)
  local n = seq_len(seq)
  if n - ddof <= 0 then return make_rational(0, 1) end
  local m = mean_of(seq)
  local total = make_rational(0, 1)
  for idx = 0, n - 1 do
    local d = num_sub(seq_get(seq, idx), m)
    total = num_add(total, num_mul(d, d))
  end
  return num_div(total, make_rational(n - ddof, 1))
end
def("mean", function(s) push(s, mean_of(pop(s))) end)
def("population-var", function(s) push(s, var_ddof(pop(s), 0)) end)
def("sample-var", function(s) push(s, var_ddof(pop(s), 1)) end)
def("population-std", function(s) push(s, math.sqrt(to_float(var_ddof(pop(s), 0)))) end)
def("sample-std", function(s) push(s, math.sqrt(to_float(var_ddof(pop(s), 1)))) end)
def("std", function(s) push(s, math.sqrt(to_float(var_ddof(pop(s), 1)))) end)

-- ── math.vectors ───────────────────────────────────────────────────
-- Element-wise vector ops confirmed against real Factor stack effects
-- (v+/v-/v*/v//vneg/vmax/vmin/vabs/vsad and the scalar n*v/v*n family).
local function vec_map(seq, f)
  local result = tagged("seq")
  for idx = 0, seq_len(seq) - 1 do result[make_rational(idx, 1)] = f(seq_get(seq, idx)) end
  return result
end
local function vec_2map(seq1, seq2, f)
  local result = tagged("seq")
  for idx = 0, seq_len(seq1) - 1 do
    result[make_rational(idx, 1)] = f(seq_get(seq1, idx), seq_get(seq2, idx))
  end
  return result
end
def("vneg", function(s) push(s, vec_map(pop(s), function(x) return -x end)) end)
def("vabs", function(s) push(s, vec_map(pop(s), num_abs)) end)
def("v+", function(s) local v2, v1 = pop(s), pop(s); push(s, vec_2map(v1, v2, num_add)) end)
def("v-", function(s) local v2, v1 = pop(s), pop(s); push(s, vec_2map(v1, v2, num_sub)) end)
def("v*", function(s) local v2, v1 = pop(s), pop(s); push(s, vec_2map(v1, v2, num_mul)) end)
def("v/", function(s) local v2, v1 = pop(s), pop(s); push(s, vec_2map(v1, v2, num_div)) end)
def("vmax", function(s)
  local v2, v1 = pop(s), pop(s)
  push(s, vec_2map(v1, v2, function(a, b) return compare3(a, b) ~= LT and a or b end))
end)
def("vmin", function(s)
  local v2, v1 = pop(s), pop(s)
  push(s, vec_2map(v1, v2, function(a, b) return compare3(a, b) ~= GT and a or b end))
end)
def("v*n", function(s) local n, v = pop(s), pop(s); push(s, vec_map(v, function(x) return num_mul(x, n) end)) end)
def("n*v", function(s) local v, n = pop(s), pop(s); push(s, vec_map(v, function(x) return num_mul(n, x) end)) end)
def("v+n", function(s) local n, v = pop(s), pop(s); push(s, vec_map(v, function(x) return num_add(x, n) end)) end)
def("n+v", function(s) local v, n = pop(s), pop(s); push(s, vec_map(v, function(x) return num_add(n, x) end)) end)
def("v-n", function(s) local n, v = pop(s), pop(s); push(s, vec_map(v, function(x) return num_sub(x, n) end)) end)
def("n-v", function(s) local v, n = pop(s), pop(s); push(s, vec_map(v, function(x) return num_sub(n, x) end)) end)
def("v/n", function(s) local n, v = pop(s), pop(s); push(s, vec_map(v, function(x) return num_div(x, n) end)) end)
def("n/v", function(s) local v, n = pop(s), pop(s); push(s, vec_map(v, function(x) return num_div(n, x) end)) end)
def("vsad", function(s)
  local v2, v1 = pop(s), pop(s)
  local total = make_rational(0, 1)
  for idx = 0, seq_len(v1) - 1 do
    local d = num_sub(seq_get(v1, idx), seq_get(v2, idx))
    total = num_add(total, num_abs(d))
  end
  push(s, total)
end)

-- ── splitting/sequences: split/split1/join ──────────────────────────
-- Confirmed against real Factor docs (docs.factorcode.org's splitting
-- vocab page, which also gives `join`'s real home as core `sequences`).
-- Scoped to string input/output (this kernel's main use for these --
-- parsing note names, chord symbols, etc.) rather than real Factor's
-- fully generic element-type-preserving version.
def("split", function(s)
  local seps, seq = pop(s), pop(s)
  local result = tagged("seq")
  local ri = 0
  local buf = {}
  local function flush()
    result[make_rational(ri, 1)] = table.concat(buf)
    ri = ri + 1
    buf = {}
  end
  for idx = 0, seq_len(seq) - 1 do
    local c = seq_get(seq, idx)
    local is_sep = false
    for j = 0, seq_len(seps) - 1 do
      if values_eq(seq_get(seps, j), c) then is_sep = true; break end
    end
    if is_sep then flush() else buf[#buf + 1] = c end
  end
  flush()
  push(s, result)
end)

-- split1 ( seq subseq -- before after ): confirmed real Factor def --
-- splits at the first occurrence of the whole subseq (not a per-char
-- set like split above); f for `after` if subseq isn't found.
def("split1", function(s)
  local subseq, seq = pop(s), pop(s)
  local i, j = seq:find(subseq, 1, true)
  if not i then push(s, seq); push(s, F); return end
  push(s, seq:sub(1, i - 1))
  push(s, seq:sub(j + 1))
end)

-- join ( seq glue -- newseq ): confirmed real Factor stack effect --
-- interleaves glue between seq's elements.
def("join", function(s)
  local glue, seq = pop(s), pop(s)
  local parts = {}
  for idx = 0, seq_len(seq) - 1 do parts[#parts + 1] = seq_get(seq, idx) end
  push(s, table.concat(parts, glue))
end)

-- ── ascii (per-character predicates and case conversion) ───────────
-- Real Factor's `ascii` vocab operates on integer code-point
-- characters; this kernel's strings-as-sequences give 1-character Lua
-- strings instead (see seq_get above), so letter?/digit?/ch>lower/etc.
-- below are re-expressed directly against Lua's own string patterns
-- rather than porting the CHAR:-literal comparison logic verbatim --
-- same kind of representation-driven adaptation as sqrt/factorial above.
def("blank?", function(s)
  local ch = pop(s)
  push(s, bool(ch == " " or ch == "\t" or ch == "\n" or ch == "\r"))
end)
def("letter?", function(s) push(s, bool(pop(s):match("^%l$") ~= nil)) end)
def("LETTER?", function(s) push(s, bool(pop(s):match("^%u$") ~= nil)) end)
def("Letter?", function(s) push(s, bool(pop(s):match("^%a$") ~= nil)) end)
def("digit?", function(s) push(s, bool(pop(s):match("^%d$") ~= nil)) end)
def("alpha?", function(s) push(s, bool(pop(s):match("^%w$") ~= nil)) end)
def("ch>lower", function(s) push(s, pop(s):lower()) end)
def("ch>upper", function(s) push(s, pop(s):upper()) end)
def(">lower", function(s) push(s, pop(s):lower()) end)
def(">upper", function(s) push(s, pop(s):upper()) end)

-- ── throw/recover/ignore-errors (continuations-lite) ────────────────
-- Confirmed against real Factor source (core/kernel/kernel.factor:
-- `GENERIC: throw ( error -- * )`; core/continuations/continuations.
-- factor's own `recover`/`ignore-errors` defs) -- real Factor's actual
-- continuations vocab is built on genuine first-class continuations
-- (callcc0/callcc1, saved data/call/retain/catch stacks), a VM
-- capability with no Lua equivalent; this is a from-scratch
-- reimplementation of the *observable* behavior via pcall/error, not a
-- port -- same standard as class algebra dispatch's resolve_method.
--
-- error(v, 0) propagates v completely unchanged when v isn't a string
-- (Lua only adds its "file:line: " prefix to string messages), so a
-- thrown Rational/string/tuple arrives at `recovery` exactly as given.
-- This kernel's own internal `error("...")` call sites elsewhere in
-- this file are NOT rewritten -- pcall catches them exactly the same
-- way it catches a throw, free interoperability, but an honest
-- asymmetry: an internal error arrives at `recovery` as a plain
-- Lua-formatted string (with Lua's own file:line prefix), not a
-- structured, predicate-testable tuple the way a real Factor internal
-- condition is. Only ERROR:-declared, explicitly thrown values get a
-- real class.
def("throw", function(s) error(pop(s), 0) end)

-- recover ( ..a try: ( ..a -- ..b ) recovery: ( ..a error -- ..b ) -- ..b ):
-- confirmed real Factor stack effect and semantics -- on failure,
-- `recovery` sees the stack rolled back to exactly its state BEFORE
-- `try` ran (`..a`), with the error pushed on top, not whatever partial
-- damage a failed `try` left behind. `..a` is stack-effect-polymorphic
-- (unknown size), so the only correct implementation is a full
-- snapshot-and-restore, not just remembering a depth.
def("recover", function(s, ctx)
  local recovery, try = pop(s), pop(s)
  local snapshot = {}
  for i = 1, #s do snapshot[i] = s[i] end
  local ok, err = pcall(run, quot_steps(try), s, ctx)
  if ok then return end
  for i = #s, 1, -1 do s[i] = nil end
  for i = 1, #snapshot do s[i] = snapshot[i] end
  push(s, err)
  run(quot_steps(recovery), s, ctx)
end)

-- ignore-errors ( ... quot -- ... ): confirmed real Factor def,
-- `[ drop ] recover` -- same snapshot/pcall shape, no recovery step
-- beyond restoring the snapshot (dropping the error entirely).
def("ignore-errors", function(s, ctx)
  local quot = pop(s)
  local snapshot = {}
  for i = 1, #s do snapshot[i] = s[i] end
  local ok = pcall(run, quot_steps(quot), s, ctx)
  if not ok then
    for i = #s, 1, -1 do s[i] = nil end
    for i = 1, #snapshot do s[i] = snapshot[i] end
  end
end)

-- ── Object protocol: clone/equal?/hashcode/hashcode*/identity-hashcode ──
-- Confirmed against real Factor source (core/kernel/kernel.factor).

-- clone ( obj -- cloned ): confirmed real Factor -- the GENERIC default
-- (`M: object clone ;`) is a no-op identity; only specific compound
-- types get a real shallow copy elsewhere in real Factor's own source
-- (sequences/tuples). Reimplemented directly here rather than through
-- this kernel's own GENERIC:/M: (which dispatches on ONE stack
-- position via wire_generic, fine for user-defined words but not worth
-- routing a single built-in kernel primitive through): seq/map/set get
-- a fresh shallow copy; a TUPLE:/ERROR: instance gets a fresh shallow
-- copy sharing the same metatable; everything else (numbers, strings,
-- t/f, quotations, wordrefs) is returned as-is, matching real Factor's
-- own default.
def("clone", function(s)
  local v = pop(s)
  local kind = mtype(v)
  if kind == "seq" or kind == "map" or kind == "set" then
    local copy = tagged(kind)
    for k, val in pairs(v) do copy[k] = val end
    push(s, copy)
  elseif kind and tuple_classes[kind] then
    local copy = setmetatable({}, getmetatable(v))
    for k, val in pairs(v) do copy[k] = val end
    push(s, copy)
  else
    push(s, v)
  end
end)

-- equal? ( obj1 obj2 -- ? ): confirmed real Factor GENERIC word, whose
-- own kernel-vocab default is `M: object equal? 2drop f ;` -- real
-- structural comparison for sequences/tuples comes from M: overrides
-- in OTHER vocabs (sequences/tuples) this kernel doesn't have a
-- separate copy of, so it's reimplemented directly here as one
-- recursive structural-equality function instead: two seqs are equal?
-- iff same length and pairwise-equal elements; two tuples of the SAME
-- class are equal? iff every slot is; assocs/sets iff same
-- keys/members (values pairwise-equal? too for assocs); everything
-- else falls back to identity, matching the real default. NOTE: `=`
-- itself is NOT rewired to call this (real Factor's own `=` is
-- `eq? or equal?`) -- deliberately out of scope, see the commit
-- message; `=` keeps its existing narrower behavior unchanged.
local function equal_values(a, b)
  if both_rational(a, b) then return a == b end
  if is_float(a) or is_float(b) then
    if not (is_float(a) or is_rational(a)) or not (is_float(b) or is_rational(b)) then return false end
    return to_float(a) == to_float(b)
  end
  if type(a) == "string" and type(b) == "string" then return a == b end
  local ka, kb = mtype(a), mtype(b)
  if ka ~= kb or not ka then return a == b end
  if ka == "seq" then
    local na, nb = seq_len(a), seq_len(b)
    if na ~= nb then return false end
    for idx = 0, na - 1 do
      if not equal_values(seq_get(a, idx), seq_get(b, idx)) then return false end
    end
    return true
  end
  if ka == "set" then
    for k in pairs(a) do if b[k] == nil then return false end end
    for k in pairs(b) do if a[k] == nil then return false end end
    return true
  end
  if ka == "map" then
    for k, v in pairs(a) do
      local bv = b[k]
      if bv == nil or not equal_values(v, bv) then return false end
    end
    for k in pairs(b) do if a[k] == nil then return false end end
    return true
  end
  if tuple_classes[ka] then
    for _, slot in ipairs(all_slots(ka)) do
      if not equal_values(a[slot], b[slot]) then return false end
    end
    return true
  end
  return a == b
end
def("equal?", function(s) local b, a = pop(s), pop(s); push(s, bool(equal_values(a, b))) end)

-- hashcode*/hashcode ( depth obj -- code ) / ( obj -- code ): confirmed
-- real Factor's own KERNEL-vocab default -- 0 for anything without a
-- specific override, 31337 for f (`M: object hashcode* 2drop 0 ;` /
-- `M: f hashcode* 2drop 31337 ;`). Richer, actually-useful per-type
-- hashing (numbers, strings, sequence contents) lives in math/strings/
-- sequences vocabs this kernel doesn't separately implement -- an
-- honest gap, not a claimed general-purpose hash function. depth is
-- accepted and ignored (matches the real def's own `2drop`).
def("hashcode*", function(s)
  local obj = pop(s)
  pop(s)
  push(s, obj == F and make_rational(31337, 1) or make_rational(0, 1))
end)
def("hashcode", function(s)
  local obj = pop(s)
  push(s, obj == F and make_rational(31337, 1) or make_rational(0, 1))
end)

-- identity-hashcode ( obj -- code ): confirmed real Factor stack effect
-- -- the real implementation inspects VM object tags/memory addresses,
-- not portable; reimplemented as an honest adaptation with the same
-- observable contract (same object always yields the same code, calls
-- to distinct objects are very likely to differ): a per-table counter
-- cached with a weak-keyed table for tables, a simple content hash for
-- strings, the value itself (floored) for numbers.
local identity_hash_counter = 0
local identity_hash_cache = setmetatable({}, { __mode = "k" })
local function identity_hash_of(v)
  if type(v) == "table" then
    local cached = identity_hash_cache[v]
    if cached then return cached end
    identity_hash_counter = identity_hash_counter + 1
    identity_hash_cache[v] = identity_hash_counter
    return identity_hash_counter
  end
  if type(v) == "number" then return math.floor(v) end
  if type(v) == "string" then
    local h = 0
    for i = 1, #v do h = (h * 31 + v:byte(i)) % 2147483647 end
    return h
  end
  return 0
end
def("identity-hashcode", function(s) push(s, make_rational(identity_hash_of(pop(s)), 1)) end)

-- ── Generic tuple construction: new/boa ─────────────────────────────
-- Confirmed real Factor stack effects (`new ( class -- tuple )`,
-- `boa ( slots... class -- tuple )`) -- real Factor classes are
-- first-class WORD values; this kernel has no equivalent (a class name
-- is just a string known to class_supers/class_own_slots/
-- class_metatables), so `class` here is a plain string literal, e.g.
-- `"animal" new`, not a `\ animal`-style reference -- an explicit,
-- flagged design choice, not a literal port of how real Factor passes
-- classes around.
def("new", function(s)
  local class = pop(s)
  local mt = class_metatables[class]
  if not mt then error("new: unknown tuple class " .. tostring(class)) end
  local t = setmetatable({}, mt)
  -- Every slot bound to f, not left as a raw unset Lua nil -- real
  -- Factor tuples never have a truly-undefined slot, `new`'s blank
  -- ones default to f (confirmed observable behavior, even though
  -- kernel.factor's own `new` definition doesn't literally show the
  -- per-slot initialization, which happens at the VM/tuple-layout
  -- level real Factor allocates through).
  for _, slot in ipairs(all_slots(class)) do t[slot] = F end
  push(s, t)
end)
def("boa", function(s)
  local class = pop(s)
  local mt = class_metatables[class]
  if not mt then error("boa: unknown tuple class " .. tostring(class)) end
  local slots = all_slots(class)
  local t = setmetatable({}, mt)
  for idx = #slots, 1, -1 do t[slots[idx]] = pop(s) end
  push(s, t)
end)

-- tuple? ( object -- ? ): confirmed real Factor -- true for ANY
-- TUPLE:/ERROR:-declared class instance, not just one specific class
-- (real Factor gets this from `BUILTIN: tuple`, the universal
-- superclass every tuple's own class ultimately inherits from).
def("tuple?", function(s) push(s, bool(tuple_classes[dispatch_class(pop(s))] == true)) end)

-- ── assert/assert=: confirmed real Factor (`ERROR: assert got expect
-- ;` plus `assert= ( a b -- ) 2dup = [ 2drop ] [ assert ] if`) --
-- written directly as primitives (mirroring define_tuple_class's own
-- shape) rather than bootstrapped through this kernel's ERROR: parser
-- at load time, so `assert`/`assert?`/accessors land in the always-
-- available kernel dictionary like every other word in this file, not
-- whatever vocabulary happens to be current when the module loads.
local assert_mt = { __mtype = "assert" }
assert_mt.__tostring = function(t)
  return "T{ assert got " .. display(t.got) .. " expect " .. display(t.expect) .. " }"
end
class_metatables["assert"] = assert_mt
tuple_classes["assert"] = true
def("got>>", function(s) local t = pop(s); push(s, t.got) end)
def("expect>>", function(s) local t = pop(s); push(s, t.expect) end)
def("assert?", function(s) push(s, bool(dispatch_class(pop(s)) == "assert")) end)
def("assert", function(s)
  local expect, got = pop(s), pop(s)
  error(setmetatable({ got = got, expect = expect }, assert_mt), 0)
end)
def("assert=", function(s)
  local b, a = pop(s), pop(s)
  if values_eq(a, b) then return end
  error(setmetatable({ got = a, expect = b }, assert_mt), 0)
end)

-- ── get-datastack/get-retainstack/die: confirmed real Factor stack
-- effects, reimplemented against this kernel's own execution model.
-- get-datastack is genuinely meaningful here (`s` IS the datastack);
-- get-retainstack always returns an empty vector, honestly flagged --
-- this kernel's dip is implemented via direct save/restore on the
-- single data stack (see dip above), not a separate retain-stack data
-- structure, so there is never anything to report. die calls Lua's
-- error() rather than a real process-level abort (`os.exit`) -- this
-- kernel runs embedded in a host Lua process (REPL, test runner, ...)
-- that a stray `die` shouldn't be able to kill outright; propagating
-- as an ordinary uncatchable-by-convention error is the safe analog.
def("get-datastack", function(s)
  local arr = tagged("seq")
  for i = 1, #s do arr[make_rational(i - 1, 1)] = s[i] end
  push(s, arr)
end)
def("get-retainstack", function(s) push(s, tagged("seq")) end)
def("die", function() error("die", 0) end)

-- ── define_tuple_class: shared by TUPLE: and ERROR: below ───────────
-- Extracted so the two don't independently drift -- confirmed real
-- Factor parses both with the SAME tuple-definition parser
-- (core/syntax/syntax.factor's "TUPLE:"/"ERROR:" core-syntax entries
-- both call `parse-tuple-definition`), so ERROR: also accepts the
-- optional `< superclass` TUPLE: gained in Phase D. `throwing` is the
-- only behavioral fork: TUPLE: defines `<class>` (build, push);
-- ERROR: defines `class` itself (build, throw immediately) -- matching
-- real Factor's own naming (`ERROR: not-found key ;` gives a word
-- called `not-found`, not `<not-found>`; confirmed against
-- core/classes/error/error.factor's `define-error-class`, `[ boa
-- throw ] curry`).
local function define_tuple_class(class, super, own_slots, throwing)
  if super then class_supers[class] = super end
  class_own_slots[class] = own_slots

  -- Full slot list = every ancestor's own slots (root first), then
  -- this class's own -- a subclass's accessors/constructor cover
  -- inherited slots too, matching real Factor's own `<dog>` taking
  -- animal's slots first, then dog's.
  local slots = all_slots(class)

  -- One shared metatable per class (not per instance), same reasoning
  -- as seq_mt/map_mt/set_mt above -- lets every instance print via one
  -- __tostring. Real Factor prints tuples as `T{ classname f val1 val2
  -- ... }` (confirmed) -- the `f` is an internal slot this kernel has
  -- no equivalent for and won't fake; shown with slot NAMES instead
  -- (`T{ class slot1 v1 slot2 v2 }`), an honest adaptation, not a
  -- claimed exact match.
  local class_mt = { __mtype = class }
  class_mt.__tostring = function(t)
    local parts = { "T{", class }
    for _, slot in ipairs(slots) do
      parts[#parts + 1] = slot
      parts[#parts + 1] = display(t[slot])
    end
    parts[#parts + 1] = "}"
    return table.concat(parts, " ")
  end
  class_metatables[class] = class_mt
  tuple_classes[class] = true

  for _, slot in ipairs(slots) do
    -- nil -> f: a slot can be genuinely unset on a blank `new`-built
    -- instance (see new/boa further down), and push(s, nil) would
    -- silently corrupt the stack (Lua's `t[#t+1] = nil` is a no-op) --
    -- same nil-to-f translation nth/at already do for a missing index/key.
    define_word(slot .. ">>", function(s)
      local t = pop(s)
      local v = t[slot]
      push(s, v == nil and F or v)
    end)
    -- ( tuple value -- tuple ): value is pushed AFTER the tuple, so
    -- it's on top -- matches the natural chaining idiom
    -- `<point> 3 >>x 4 >>y`, not the reverse.
    define_word(">>" .. slot, function(s) local v, t = pop(s), pop(s); t[slot] = v; push(s, t) end)
  end

  if throwing then
    define_word(class, function(s)
      local t = setmetatable({}, class_mt)
      for idx = #slots, 1, -1 do t[slots[idx]] = pop(s) end
      error(t, 0)
    end)
  else
    define_word("<" .. class .. ">", function(s)
      local t = setmetatable({}, class_mt)
      for idx = #slots, 1, -1 do t[slots[idx]] = pop(s) end
      push(s, t)
    end)
  end

  -- class? predicate -- confirmed real Factor auto-generates one of
  -- these for every class (e.g. iota/iota?/<iota> in `sequences`).
  -- Hierarchy-aware via is_a -- `animal?` is true of a dog instance
  -- too, not just an `animal` itself, matching real Factor's own
  -- `instance?` respecting subclassing.
  define_word(class .. "?", function(s) push(s, bool(is_a(dispatch_class(pop(s)), class))) end)
end

-- parse_tuple_header: the token-reading half TUPLE:/ERROR: also share
-- -- class name, optional `< superclass`, own slots up to ';'.
local function parse_tuple_header(keyword)
  local class = input.tokens[input.pos] and input.tokens[input.pos].text
  if not class then error(keyword .. " expected a class name") end
  input.pos = input.pos + 1

  local super
  if input.tokens[input.pos] and input.tokens[input.pos].text == "<" then
    input.pos = input.pos + 1
    super = input.tokens[input.pos] and input.tokens[input.pos].text
    if not super then error(keyword .. " " .. class .. " < expected a superclass name") end
    input.pos = input.pos + 1
  end

  local own_slots = {}
  while input.tokens[input.pos] and input.tokens[input.pos].text ~= ";" do
    own_slots[#own_slots + 1] = input.tokens[input.pos].text
    input.pos = input.pos + 1
  end
  if not input.tokens[input.pos] then error("expected ';' to close " .. keyword .. " " .. class) end
  input.pos = input.pos + 1

  return class, super, own_slots
end

function mforth.eval(source, stack)
  stack = stack or new_stack()
  input.tokens = tokenize(source)
  input.pos = 1
  local top_ctx = { env = {} }

  while input.pos <= #input.tokens do
    local tok = input.tokens[input.pos]

    if tok.kind == "str" then
      push(stack, tok.text)
      input.pos = input.pos + 1

    elseif tok.text == "[" then
      local q_steps, term, next_pos = compile_block(input.tokens, input.pos + 1, { ["]"] = true }, nil)
      if not term then error("[ without matching ]") end
      local body_text = tokens_to_text(input.tokens, input.pos + 1, next_pos - 2)
      push(stack, make_quotation(q_steps, body_text == "" and "[ ]" or "[ " .. body_text .. " ]"))
      input.pos = next_pos

    elseif tok.text == ":" then
      input.pos = input.pos + 1
      local name = input.tokens[input.pos] and input.tokens[input.pos].text
      if not name then error("expected a name after ':'") end
      input.pos = input.pos + 1

      local locals, incoming_names
      if input.tokens[input.pos] and input.tokens[input.pos].text == "{" then
        local incoming, extra
        incoming, extra, input.pos = parse_locals_block(input.tokens, input.pos)
        incoming_names = incoming
        local incoming_set, extra_set = {}, {}
        for _, n in ipairs(incoming) do incoming_set[n] = true end
        for _, n in ipairs(extra) do extra_set[n] = true end
        locals = { incoming = incoming_set, extra = extra_set }
      end

      local steps, term, next_pos =
        compile_block(input.tokens, input.pos, { [";"] = true, ["does>"] = true }, locals)
      if not term then error("expected ';' to close definition of " .. name) end

      -- does>, kept from the prior Forth-flavored kernel phase: replaces
      -- whatever CREATE just defined with "push its data area, then run
      -- the does-body" -- see create/,/does> above.
      if term == "does>" then
        local does_steps, term2, next_pos2 = compile_block(input.tokens, next_pos, { [";"] = true }, nil)
        if not term2 then error("expected ';' to close does> body of " .. name) end
        steps[#steps + 1] = function()
          if not create_state.name then error("does> used with no prior create") end
          local area, target = create_state.area, create_state.name
          define_word(target, function(s2)
            push(s2, area)
            run(does_steps, s2, { env = {} })
          end)
        end
        next_pos = next_pos2
      end

      define_word(name, function(s)
        local ctx = { env = {} }
        if incoming_names then
          for idx = #incoming_names, 1, -1 do
            ctx.env[incoming_names[idx]] = pop(s)
          end
        end
        run(steps, s, ctx)
      end)
      input.pos = next_pos

    elseif tok.text == "TUPLE:" then
      -- TUPLE: class < superclass slot1 slot2 ... ; -- confirmed real
      -- Factor syntax, the optional `< superclass` from Phase D.
      -- Parsing (parse_tuple_header) and class-building
      -- (define_tuple_class) are shared with ERROR: below.
      input.pos = input.pos + 1
      local class, super, own_slots = parse_tuple_header("TUPLE:")
      define_tuple_class(class, super, own_slots, false)

    elseif tok.text == "ERROR:" then
      -- ERROR: class < superclass slot1 slot2 ... ; -- confirmed real
      -- Factor syntax (core/syntax/syntax.factor's "ERROR:" core-syntax
      -- entry parses with the exact same parse-tuple-definition TUPLE:
      -- uses). Builds the same kind of tuple class as TUPLE: (same
      -- accessors, same class? predicate), but instead of a `<class>`
      -- constructor defines a word literally named `class` that builds
      -- the tuple and immediately throws it -- see define_tuple_class's
      -- `throwing` branch.
      input.pos = input.pos + 1
      local class, super, own_slots = parse_tuple_header("ERROR:")
      define_tuple_class(class, super, own_slots, true)

    elseif tok.text == "MIXIN:" then
      -- MIXIN: name -- confirmed real Factor syntax (core/classes/mixin)
      -- -- declares an empty membership category with no slots/
      -- constructor of its own; INSTANCE: below adds members to it one
      -- at a time. Real Factor auto-generates a `name?` predicate for
      -- mixins too (confirmed: `sequence?` itself is generated exactly
      -- this way), hierarchy/membership-aware via the same is_a used by
      -- TUPLE:'s class? above.
      input.pos = input.pos + 1
      local mixin = input.tokens[input.pos] and input.tokens[input.pos].text
      if not mixin then error("MIXIN: expected a name") end
      input.pos = input.pos + 1
      define_word(mixin .. "?", function(s) push(s, bool(is_a(dispatch_class(pop(s)), mixin))) end)

    elseif tok.text == "UNION:" then
      -- UNION: name class1 class2 ... ; -- confirmed real Factor syntax
      -- (core/classes/union) -- declares name and registers every
      -- listed member's membership in one step, unlike MIXIN:'s
      -- incremental INSTANCE:.
      input.pos = input.pos + 1
      local union = input.tokens[input.pos] and input.tokens[input.pos].text
      if not union then error("UNION: expected a name") end
      input.pos = input.pos + 1
      while input.tokens[input.pos] and input.tokens[input.pos].text ~= ";" do
        register_membership(input.tokens[input.pos].text, union)
        input.pos = input.pos + 1
      end
      if not input.tokens[input.pos] then error("expected ';' to close UNION: " .. union) end
      input.pos = input.pos + 1
      define_word(union .. "?", function(s) push(s, bool(is_a(dispatch_class(pop(s)), union))) end)

    elseif tok.text == "INSTANCE:" then
      -- INSTANCE: concrete-class mixin-or-union-name -- confirmed real
      -- Factor syntax (e.g. core/sets/sets.factor's own
      -- `INSTANCE: sequence set`) -- a single two-token form, no
      -- closing ';'.
      input.pos = input.pos + 1
      local member = input.tokens[input.pos] and input.tokens[input.pos].text
      if not member then error("INSTANCE: expected a class name") end
      input.pos = input.pos + 1
      local category = input.tokens[input.pos] and input.tokens[input.pos].text
      if not category then error("INSTANCE: " .. member .. " expected a mixin/union name") end
      input.pos = input.pos + 1
      register_membership(member, category)

    elseif tok.text == "PREDICATE:" then
      -- PREDICATE: name < parent-class ... ; -- confirmed real Factor
      -- syntax (core/classes/predicate/predicate.factor's own
      -- definition: `PREDICATE: predicate-class < class "metaclass"
      -- word-prop predicate-class eq? ;`) -- the body is a BARE word
      -- sequence up to ';', compiled the same way `:`/`M:` bodies
      -- already are, NOT a bracketed `[ ... ]` quotation literal.
      -- Effect ( obj -- ? ), only ever run once the dispatching value
      -- is already confirmed to be an instance of parent-class (or one
      -- of ITS ancestors) -- see resolve_method and the auto-generated
      -- `name?` predicate below, both gate on that the same way real
      -- Factor's own `instance?` does (`superclass-of instance?` first,
      -- the predicate quotation only runs if that passes).
      input.pos = input.pos + 1
      local predname = input.tokens[input.pos] and input.tokens[input.pos].text
      if not predname then error("PREDICATE: expected a name") end
      input.pos = input.pos + 1
      if not (input.tokens[input.pos] and input.tokens[input.pos].text == "<") then
        error("PREDICATE: " .. predname .. " expected '<' and a parent class")
      end
      input.pos = input.pos + 1
      local parent = input.tokens[input.pos] and input.tokens[input.pos].text
      if not parent then error("PREDICATE: " .. predname .. " < expected a parent class name") end
      input.pos = input.pos + 1

      local steps, term, next_pos = compile_block(input.tokens, input.pos, { [";"] = true }, nil)
      if not term then error("expected ';' to close PREDICATE: " .. predname) end
      input.pos = next_pos

      class_predicates[predname] = { parent = parent, steps = steps }
      predicates_by_parent[parent] = predicates_by_parent[parent] or {}
      predicates_by_parent[parent][#predicates_by_parent[parent] + 1] = predname

      define_word(predname .. "?", function(s, ctx)
        local v = pop(s)
        if not is_a(dispatch_class(v), parent) then push(s, F); return end
        push(s, v)
        run(steps, s, ctx)
      end)

    elseif tok.text == "M:" then
      input.pos = input.pos + 1
      local class = input.tokens[input.pos] and input.tokens[input.pos].text
      if not class then error("M: expected a class name") end
      input.pos = input.pos + 1
      local name = input.tokens[input.pos] and input.tokens[input.pos].text
      if not name then error("M: expected a generic word name") end
      input.pos = input.pos + 1

      local steps, term, next_pos = compile_block(input.tokens, input.pos, { [";"] = true }, nil)
      if not term then error("expected ';' to close M: " .. class .. " " .. name) end
      wire_generic(name)
      generics[name][class] = steps
      input.pos = next_pos

    elseif tok.text == "\\" then
      input.pos = input.pos + 1
      push(stack, make_wordref(input.tokens[input.pos] and input.tokens[input.pos].text))
      input.pos = input.pos + 1

    else
      local step = resolve(tok.text)
      if not step then error("unknown word: " .. tok.text) end
      -- Advance PAST this token before running it, not after -- a word
      -- like CREATE reads input.pos itself, expecting it to already
      -- point past its own token.
      input.pos = input.pos + 1
      step(stack, top_ctx)
    end
  end

  return stack
end

-- ── File loading ───────────────────────────────────────────
-- Not the on-disk vocabulary/module loader (name -> path resolution,
-- search paths, USE: triggering a load) that the architecture notes
-- call out as a separate, much bigger undertaking -- just "run this one
-- known file," the same shape as Lua's own `dofile`. Loading a file
-- only DEFINES whatever vocabulary it sets via IN:; it doesn't bring
-- that vocabulary into scope anywhere else, same as typing its contents
-- at a REPL would.
function mforth.dofile(path, stack)
  local f = assert(io.open(path, "r"))
  local source = f:read("*a")
  f:close()
  return mforth.eval(source, stack)
end

mforth.new_stack = new_stack
mforth.dictionary = dictionary
mforth.mtype = mtype
mforth.rational = make_rational
mforth.T = T
mforth.F = F

return mforth
