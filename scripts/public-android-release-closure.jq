if (type != "array" or any(.[]; type != "array")) then
  error("paginated Release response must be a stream of JSON arrays")
else
  .
end
| [.[][] |
    if (type != "object" or
        (.id | type) != "number" or .id <= 0 or
        (.tag_name | type) != "string" or .tag_name == "" or
        (.draft | type) != "boolean" or
        (.prerelease | type) != "boolean" or
        (.assets | type) != "array") then
      error("Release response contains a malformed identity or asset closure")
    else
      .
    end
    | select(.draft == false)
    | . as $release
    | ([.assets[] | select(.name == "android-update.json")]) as $manifests
    | select(($manifests | length) > 0)
    | {release_id: $release.id, tag: $release.tag_name,
        manifest_assets: [$manifests[] | {id, size, digest}]}]
| sort_by(.release_id)
| if ([.[].release_id] | unique | length) != length then
    error("public Android Release closure contains a duplicate release ID")
  elif ([.[].tag] | unique | length) != length then
    error("public Android Release closure contains a duplicate tag")
  else
    .
  end
