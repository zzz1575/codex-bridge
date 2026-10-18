//! Bounded server-prompt response validation. Never fetch schema references or persist answers.
use serde_json::{json, Value};

pub(crate) fn is_prompt(method: &str) -> bool {
    matches!(
        method,
        "item/tool/requestUserInput" | "mcpServer/elicitation/request"
    )
}

pub(crate) fn response_result(request: &Value, response: &Value) -> Result<Value, &'static str> {
    if response.to_string().len() > 64 * 1024 {
        return Err("The response exceeds the request size limit");
    }
    let object = response
        .as_object()
        .ok_or("The response must be an object")?;
    let params = &request["params"];
    match request["method"].as_str() {
        Some("item/tool/requestUserInput") => {
            if object.keys().any(|key| key != "answers") {
                return Err("Unexpected answer fields");
            }
            let answers = response["answers"]
                .as_object()
                .ok_or("Answers must be an object")?;
            // An empty mapping is an explicit user choice to leave the request unanswered.
            if answers.is_empty() {
                return Ok(json!({"answers": {}}));
            }
            let questions = params["questions"]
                .as_array()
                .ok_or("The question list is invalid")?;
            if questions.len() > 128 || answers.len() != questions.len() {
                return Err("Answer every question or explicitly skip the request");
            }
            for question in questions {
                let id = question["id"]
                    .as_str()
                    .ok_or("A question has no identifier")?;
                let answer = answers
                    .get(id)
                    .and_then(Value::as_object)
                    .ok_or("A question answer is missing")?;
                if answer.len() != 1 {
                    return Err("Unexpected question answer fields");
                }
                let values = answer
                    .get("answers")
                    .and_then(Value::as_array)
                    .ok_or("Question answers must be a string array")?;
                if values.len() > 32
                    || values
                        .iter()
                        .any(|v| v.as_str().is_none_or(|s| s.len() > 16_000))
                {
                    return Err("Question answers must be bounded strings");
                }
            }
            Ok(response.clone())
        }
        Some("mcpServer/elicitation/request") => {
            if object
                .keys()
                .any(|key| !matches!(key.as_str(), "action" | "content"))
            {
                return Err("Unexpected elicitation fields");
            }
            match response["action"].as_str() {
                Some("decline" | "cancel") => {
                    if response.get("content").is_some_and(|v| !v.is_null()) { return Err("Declined requests cannot include content"); }
                    Ok(json!({"action":response["action"]}))
                }
                Some("accept") => {
                    match params["mode"].as_str() {
                        Some("form" | "openai/form" | "openaiForm") => {
                            let schema = &params["requestedSchema"];
                            if !supported_schema(schema, 0) { return Err("This form schema is unsupported; cancel it or use a compatible client"); }
                            let content = response.get("content").ok_or("Form content is missing")?;
                            if !matches_schema(schema, content, 0) { return Err("Form content does not match the requested schema"); }
                            Ok(json!({"action":"accept","content":content}))
                        }
                        Some("url") => {
                            let url = params["url"].as_str().unwrap_or("");
                            if !(url.starts_with("https://") || url.starts_with("http://")) { return Err("This verification URL is unsupported"); }
                            if response.get("content").is_some_and(|v| !v.is_null()) { return Err("URL completion cannot include invented proof"); }
                            Ok(json!({"action":"accept"}))
                        }
                        _ => Err("This verification mode requires a compatible client; it cannot be accepted here"),
                    }
                }
                _ => Err("Choose accept, decline or cancel explicitly"),
            }
        }
        _ => Err("Unsupported server request"),
    }
}

fn supported_schema(schema: &Value, depth: usize) -> bool {
    if depth > 8 {
        return false;
    }
    let Some(map) = schema.as_object() else {
        return false;
    };
    const KEYS: &[&str] = &[
        "$schema",
        "type",
        "title",
        "description",
        "default",
        "examples",
        "properties",
        "required",
        "additionalProperties",
        "enum",
        "enumNames",
        "const",
        "oneOf",
        "anyOf",
        "allOf",
        "items",
        "minItems",
        "maxItems",
        "uniqueItems",
        "minLength",
        "maxLength",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "format",
    ];
    if map.keys().any(|k| !KEYS.contains(&k.as_str())) {
        return false;
    }
    if let Some(ty) = map.get("type") {
        let valid = |v: &Value| {
            v.as_str().is_some_and(|s| {
                [
                    "object", "array", "string", "number", "integer", "boolean", "null",
                ]
                .contains(&s)
            })
        };
        if !valid(ty)
            && !ty
                .as_array()
                .is_some_and(|a| !a.is_empty() && a.len() <= 7 && a.iter().all(valid))
        {
            return false;
        }
    }
    if let Some(props) = map.get("properties") {
        if !props
            .as_object()
            .is_some_and(|p| p.len() <= 128 && p.values().all(|s| supported_schema(s, depth + 1)))
        {
            return false;
        }
    }
    if let Some(required) = map.get("required") {
        if !required
            .as_array()
            .is_some_and(|a| a.len() <= 128 && a.iter().all(Value::is_string))
        {
            return false;
        }
    }
    if map
        .get("additionalProperties")
        .is_some_and(|v| !v.is_boolean())
    {
        return false;
    }
    for key in ["oneOf", "anyOf", "allOf"] {
        if let Some(choices) = map.get(key) {
            if !choices.as_array().is_some_and(|a| {
                !a.is_empty() && a.len() <= 128 && a.iter().all(|s| supported_schema(s, depth + 1))
            }) {
                return false;
            }
        }
    }
    if map
        .get("items")
        .is_some_and(|s| !supported_schema(s, depth + 1))
    {
        return false;
    }
    if map.get("enum").is_some_and(|v| {
        !v.as_array()
            .is_some_and(|a| !a.is_empty() && a.len() <= 128)
    }) {
        return false;
    }
    if map.get("format").is_some_and(|v| {
        !v.as_str()
            .is_some_and(|s| ["email", "uri", "date", "date-time"].contains(&s))
    }) {
        return false;
    }
    for key in ["minItems", "maxItems", "minLength", "maxLength"] {
        if map.get(key).is_some_and(|v| v.as_u64().is_none()) {
            return false;
        }
    }
    for key in ["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"] {
        if map.get(key).is_some_and(|v| !v.is_number()) {
            return false;
        }
    }
    !map.get("uniqueItems").is_some_and(|v| !v.is_boolean())
}

fn matches_schema(schema: &Value, value: &Value, depth: usize) -> bool {
    if depth > 8 {
        return false;
    }
    let has_type = |name: &str| match name {
        "object" => value.is_object(),
        "array" => value.is_array(),
        "string" => value.is_string(),
        "boolean" => value.is_boolean(),
        "null" => value.is_null(),
        "number" => value.is_number(),
        "integer" => value.as_f64().is_some_and(|n| n.fract() == 0.0),
        _ => false,
    };
    if let Some(ty) = schema.get("type") {
        if !ty.as_str().is_some_and(has_type)
            && !ty
                .as_array()
                .is_some_and(|a| a.iter().any(|v| v.as_str().is_some_and(has_type)))
        {
            return false;
        }
    }
    if schema.get("const").is_some_and(|v| v != value) {
        return false;
    }
    if schema
        .get("enum")
        .is_some_and(|v| !v.as_array().unwrap().contains(value))
    {
        return false;
    }
    for (key, count) in [("oneOf", 1), ("anyOf", 0), ("allOf", 2)] {
        if let Some(choices) = schema.get(key).and_then(Value::as_array) {
            let matched = choices
                .iter()
                .filter(|s| matches_schema(s, value, depth + 1))
                .count();
            if (count == 1 && matched != 1)
                || (count == 0 && matched == 0)
                || (count == 2 && matched != choices.len())
            {
                return false;
            }
        }
    }
    if let Some(object) = value.as_object() {
        let props = schema.get("properties").and_then(Value::as_object);
        if schema
            .get("required")
            .and_then(Value::as_array)
            .is_some_and(|a| a.iter().any(|k| !object.contains_key(k.as_str().unwrap())))
        {
            return false;
        }
        // An elicitation must not add unrequested fields even when the source omitted this keyword.
        let additional = schema.get("additionalProperties").and_then(Value::as_bool);
        if (additional == Some(false) || (props.is_some() && additional != Some(true)))
            && object
                .keys()
                .any(|k| props.is_none_or(|p| !p.contains_key(k)))
        {
            return false;
        }
        if let Some(props) = props {
            if object.iter().any(|(k, v)| {
                props
                    .get(k)
                    .is_some_and(|s| !matches_schema(s, v, depth + 1))
            }) {
                return false;
            }
        }
    }
    if let Some(array) = value.as_array() {
        if array.len() > 128 {
            return false;
        }
        if schema
            .get("minItems")
            .and_then(Value::as_u64)
            .is_some_and(|n| array.len() < n as usize)
            || schema
                .get("maxItems")
                .and_then(Value::as_u64)
                .is_some_and(|n| array.len() > n as usize)
        {
            return false;
        }
        if schema
            .get("items")
            .is_some_and(|s| array.iter().any(|v| !matches_schema(s, v, depth + 1)))
        {
            return false;
        }
        if schema["uniqueItems"] == true
            && array
                .iter()
                .enumerate()
                .any(|(i, v)| array[..i].contains(v))
        {
            return false;
        }
    }
    if let Some(text) = value.as_str() {
        let length = text.chars().count() as u64;
        if schema
            .get("minLength")
            .and_then(Value::as_u64)
            .is_some_and(|n| length < n)
            || schema
                .get("maxLength")
                .and_then(Value::as_u64)
                .is_some_and(|n| length > n)
        {
            return false;
        }
        if let Some(format) = schema.get("format").and_then(Value::as_str) {
            if !valid_format(format, text) {
                return false;
            }
        }
    }
    if let Some(number) = value.as_f64() {
        for (key, fail) in [
            ("minimum", 0),
            ("maximum", 1),
            ("exclusiveMinimum", 2),
            ("exclusiveMaximum", 3),
        ] {
            if schema
                .get(key)
                .and_then(Value::as_f64)
                .is_some_and(|n| match fail {
                    0 => number < n,
                    1 => number > n,
                    2 => number <= n,
                    _ => number >= n,
                })
            {
                return false;
            }
        }
    }
    true
}

fn valid_format(format: &str, value: &str) -> bool {
    match format {
        "email" => value.split_once('@').is_some_and(|(local, host)| {
            !local.is_empty()
                && !host.is_empty()
                && !host.contains('@')
                && !value.chars().any(char::is_whitespace)
        }),
        "uri" => value.split_once(':').is_some_and(|(scheme, _)| {
            !scheme.is_empty()
                && scheme.starts_with(|c: char| c.is_ascii_alphabetic())
                && scheme
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || "+-.".contains(c))
                && !value.chars().any(char::is_whitespace)
        }),
        "date" | "date-time" => {
            if !value.is_ascii() {
                return false;
            }
            let date = if format == "date-time" {
                value.split_once('T').map(|x| x.0).unwrap_or("")
            } else {
                value
            };
            let nums: Vec<_> = date
                .split('-')
                .filter_map(|s| s.parse::<u32>().ok())
                .collect();
            let date_ok = nums.len() == 3
                && date.len() == 10
                && nums[0] > 0
                && (1..=12).contains(&nums[1])
                && {
                    let leap = nums[0] % 4 == 0 && (nums[0] % 100 != 0 || nums[0] % 400 == 0);
                    let days = [
                        31,
                        if leap { 29 } else { 28 },
                        31,
                        30,
                        31,
                        30,
                        31,
                        31,
                        30,
                        31,
                        30,
                        31,
                    ];
                    (1..=days[(nums[1] - 1) as usize]).contains(&nums[2])
                };
            if !date_ok || format == "date" {
                return date_ok;
            }
            let Some(tail) = value.get(11..) else {
                return false;
            };
            if value.as_bytes().get(10) != Some(&b'T') {
                return false;
            }
            let (clock, zone) = if let Some(clock) = tail.strip_suffix('Z') {
                (clock, "Z")
            } else if let Some(index) = tail.find(['+', '-']) {
                (&tail[..index], &tail[index..])
            } else {
                return false;
            };
            let numbers: Vec<_> = clock.split(':').collect();
            let seconds_ok = numbers.get(2).is_some_and(|seconds| {
                let (whole, fraction) = seconds
                    .split_once('.')
                    .map_or((*seconds, None), |(a, b)| (a, Some(b)));
                whole.len() == 2
                    && whole.bytes().all(|c| c.is_ascii_digit())
                    && fraction
                        .is_none_or(|s| !s.is_empty() && s.bytes().all(|c| c.is_ascii_digit()))
            });
            let clock_ok = seconds_ok
                && numbers.len() == 3
                && numbers[0].len() == 2
                && numbers[1].len() == 2
                && numbers[0].parse::<u32>().is_ok_and(|n| n < 24)
                && numbers[1].parse::<u32>().is_ok_and(|n| n < 60)
                && numbers[2]
                    .parse::<f64>()
                    .is_ok_and(|n| (0.0..60.0).contains(&n));
            let zone_ok = zone == "Z"
                || (zone.len() == 6
                    && zone.as_bytes().get(3) == Some(&b':')
                    && zone[1..3].parse::<u32>().is_ok_and(|n| n <= 23)
                    && zone[4..6].parse::<u32>().is_ok_and(|n| n <= 59));
            clock_ok && zone_ok
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prompt_answers_are_scoped_and_explicit() {
        let req =
            json!({"method":"item/tool/requestUserInput","params":{"questions":[{"id":"q"}]}});
        assert_eq!(
            response_result(&req, &json!({"answers":{}})).unwrap(),
            json!({"answers":{}})
        );
        assert!(response_result(
            &req,
            &json!({"answers":{"q":{"answers":["choice","notes"]}}})
        )
        .is_ok());
        assert!(
            response_result(&req, &json!({"answers":{"other":{"answers":["choice"]}}})).is_err()
        );
        assert!(response_result(&req, &json!({"answers":{"q":{"answers":[true]}}})).is_err());
    }
    #[test]
    fn elicitation_validates_types_choices_and_limits_without_fetching_refs() {
        let mut req = json!({"method":"mcpServer/elicitation/request","params":{"mode":"form","requestedSchema":{"type":"object","properties":{"count":{"type":"integer","minimum":1},"choices":{"type":"array","items":{"enum":["a","b"]},"minItems":1}},"required":["count","choices"]}}});
        assert!(response_result(
            &req,
            &json!({"action":"accept","content":{"count":2,"choices":["b"]}})
        )
        .is_ok());
        assert!(response_result(
            &req,
            &json!({"action":"accept","content":{"count":0,"choices":["c"]}})
        )
        .is_err());
        assert!(response_result(
            &req,
            &json!({"action":"accept","content":{"count":2,"choices":["a"],"extra":true}})
        )
        .is_err());
        req["params"]["requestedSchema"] = json!({"$ref":"https://example.com/schema"});
        assert!(response_result(&req, &json!({"action":"accept","content":{}})).is_err());
        assert_eq!(
            response_result(&req, &json!({"action":"cancel"})).unwrap(),
            json!({"action":"cancel"})
        );
    }
    #[test]
    fn unsupported_device_proof_and_unsafe_urls_cannot_be_accepted() {
        for mode in ["openai/userVerification", "unrecognized"] {
            let req = json!({"method":"mcpServer/elicitation/request","params":{"mode":mode}});
            assert!(response_result(
                &req,
                &json!({"action":"accept","content":{"proof":"invented"}})
            )
            .is_err());
            assert!(response_result(&req, &json!({"action":"decline"})).is_ok());
        }
        let req = json!({"method":"mcpServer/elicitation/request","params":{"mode":"url","url":"javascript:alert(1)"}});
        assert!(response_result(&req, &json!({"action":"accept"})).is_err());
    }
}
