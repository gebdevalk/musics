-- Point links at the rendered pages: doc/setup.md -> setup.html,
-- README.md -> index.html, doc/algo-cookbook.html -> algo-cookbook.html.
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
