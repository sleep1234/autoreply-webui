# CTROBF1 Anti-Bot Challenge Page (ctwing.cn)

## Fetched from
https://tywlonestop.ctwing.cn:8081/web-apps/

## Structure
- Anti-bot meta tag: `<meta id="dRnTPWOeVn1vb25M" content="j1VNCrB28t4RQKNyXoGIi_ZfdIIfoJsp1ytyb8OuQ6h8..." r="m">`
- Inline `<script r='m'>` with CTROBF1 payload (base64-encoded, very long)
- $_ts object with nsd (timestamp/seed) and cd (base64 challenge payload)

## $_ts structure
```
$_ts.nsd = 36215
$_ts.cd = "vOuIZ5UR3bsNvZGUVLxZW..."  (long base64 string)
```

## Observations
1. This is the anti-bot JS challenge page (r='m' marker dots)
2. The $_ts.cd payload is CTROBF1-encoded dynamic challenge
3. After solving, ACCESS_TOKEN cookie is set, then SPA loads
4. The encryption algorithm is embedded in this inline script
5. Each session gets a new dynamic $_ts with different cd payload

## Next steps
1. Decode the base64 $_ts.cd to understand CTROBF1 structure
2. Find the actual encryption/decryption JS (may be in a separate bundle loaded after challenge)
3. Fetch the SPA JS bundles for the actual API encryption code