-- Point links at the rendered pages: doc/md/setup.md -> setup.html,
-- README.md -> index.html.
function Link(el)
  local t = el.target
  if t:match("^%a+://") then return el end
  local path, anchor = t:match("^([^#]*)(#?.*)$")
  local base = path:match("([^/]+)$") or path
  if base == "README.md" then base = "index.html"
  elseif base:match("%.md$") then base = base:gsub("%.md$", ".html") end
  if path ~= "" then el.target = base .. anchor end
  return el
end

-- A code span in a table that is not long stays whole: a word such as
-- tair-down must never be broken at its hyphen. Long ones may wrap.
function Code(el)
  if #el.text > 36 then el.classes:insert("long") end
  return el
end
