`in-geo-states.json`: Indian number prefix (without +91) → state, derived from the libphonenumber
geocoding data (Apache License 2.0). Used by `import-ifsc.mjs` to drop branch numbers whose area
code is in a different state than the branch (data-entry errors in the RBI list).
