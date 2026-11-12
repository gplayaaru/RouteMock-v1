# RouteMock — MVP Step-by-Step Plan (Free Stack Version)

**Status: v0.1 prototype code built 2026-11-10 (Phases 0–3 core loop only,
code only — NOT build-verified here, no APK yet). See README.md.
Freehand draw, favorites/history, billing and other PRD features are
still out of scope for v0.1.**
**Base:** RouteMock PRD v1.0 (7 Oct 2026) — lekin Google Maps/Routes/Roads ki jagah free OpenStreetMap stack.
**Decision already locked:** Development aur testing free stack par hogi. Paid services ka faisla sirf deployment ke time, tab jab free build se core concept prove ho jaye.

---

## 1. MVP ka matlab kya hai

MVP tab complete mana jayega jab ek user ye poora loop bina paid API ke kar sake:

1. Map par Start aur End choose kare, road-following route bane.
2. Duration (minute me) choose kare, ya vehicle recommendation le.
3. Mock location route par realistic speed aur direction ke saath chale.
4. Pause, resume, remaining time edit, tap-to-jump aur stop kaam kare.
5. Pause ya route complete hone par location real GPS par wapas na jump kare (anti snap-back).
6. Route save ho (favorites) aur history se dobara chala sake.

Agar inme se ek bhi kaam nahi karta, to MVP complete nahi hai — chahe UI kitna bhi sundar ho.

## 2. MVP me kya NAHI hoga (baad ke liye)

- Play Billing / Pro purchase (Rs 399 wala tier abhi nahi)
- Accuracy jitter (ye PRD me Pro feature hai)
- Off-road draw toggle — MVP me road snapping hamesha ON rahega. Ye PRD ka open item #1 hai, isko MVP ke baad decide karna hai.
- Cloud sync, accounts, iOS version
- GPX import, live traffic, multi-point waypoint editor
- Quick Settings tile — ye useful hai, lekin core proof ke baad. Agar time bache to Phase 5 me add karna, warna post-MVP.
- Paid Google Maps / Mapbox — sirf deployment decision ke time.

## 3. Free stack — PRD ke Google parts ka replacement

| Kaam | PRD me tha (Google) | MVP me hoga (Free) |
|---|---|---|
| Map dikhana | Google Maps SDK | MapLibre Native + OpenStreetMap tiles |
| Start → End road route | Google Routes API | OSRM Route service |
| Freehand ko road par chipkana | Google Roads snapToRoads | OSRM Match service + Nearest service |
| Address search | Android Geocoder / Places | Nominatim (OpenStreetMap search) |
| Mock location engine | LocationManager test providers | Wahi — isme koi change nahi |
| Data (local) | Room + DataStore | Wahi — Room + DataStore |
| UI / DI / Network | Kotlin + Compose, Hilt, Retrofit ya Ktor | Wahi |

**Important samajh:** Mocking engine ko farak nahi padta ki route OSRM se aaya ya Google se. Engine ko sirf LatLng points ki list chahiye. Isliye baad me paid par switch karne par engine dobara nahi likhna padega — sirf map/routes wali layer badlegi. PRD me ye abstraction pehle se rakhi hai, usko todna nahi hai.

**Free services ke rules (inhon ne todne par block ho sakta hai):**
- OSRM public demo server sirf development aur low traffic ke liye hai. Iski koi uptime guarantee nahi hai. Isko production server mat samajhna.
- Nominatim par request ko debounce karo, har keystroke par call mat bhejo, aur results cache karo.
- Har route request ko cache karo taaki wahi route dobara banane par API call na lage.
- Production jaane se pehle OSRM self-host karne ya paid provider lene ka faisla usage data dekh kar karna hai.

## 4. Architecture (modules)

PRD wali structure ko hi free stack par rakho:

- `:core:engine` — MockLocationEngine, state machine, tick loop. Isme koi UI dependency nahi.
- `:core:map` — MapLibre wrapper, drawing, polyline. Google/MapLibre ke peeche ek hi interface.
- `:core:routes` — OSRM client + caching. Baad me Google client isi interface par lagega.
- `:feature:planner` — Path creation + duration/vehicle sheet.
- `:feature:session` — Active session UI + notification controls.
- `:feature:library` — Favorites + history.

## 5. Phases — step by step

### Phase 0 — Setup (Gate: project khulta hai, khaali map dikhta hai)

1. Android Studio me Kotlin + Jetpack Compose project banao. Min SDK 26 (PRD ke hisaab se), target latest.
2. Git repo banao aur pehla commit karo. Bina Git ke aage mat badho — Nico wale case me source loss ho chuka hai, wahi galti dobara nahi.
3. Dependencies jodo: MapLibre, Retrofit + OkHttp, Room, DataStore, Hilt.
4. MapLibre par khaali map dikhao, apni current location (real, mock nahi) par centre karo.
5. Package name proposal (abhi decide nahi hua, scaffold se pehle confirm karna hai): `com.routemock.app`

**Pass criteria:** App phone par khulti hai, map load hota hai, crash nahi hota.

### Phase 1 — Engine proof (SABSE PEHLE, sabse risky part)

Ye phase UI polish se pehle karna hai. Agar engine fail hota hai, to baaki kaam ka koi matlab nahi.

1. `MockLocationEngine` banao: path ko cumulative distance me todo, duration se speed nikalo (speed = total distance / duration).
2. State machine banao: IDLE → ARMING → MOVING → PAUSED → HOLDING → STOPPED → IDLE. PAUSED ko alag state rakho, MOVING ke andar mat milao (PRD me ye thoda unclear hai).
3. Tick loop: MOVING me har 1 second me position aage badhao, interpolated bearing nikalo, speed aur bearing ke saath location inject karo.
4. Teenon test providers register karo: GPS, Network, Passive. `addTestProvider` + `setTestProviderEnabled(true)` karo. API 33+ ke liye `makeComplete()` lagana mat bhoolna.
5. Foreground service banao, type `location`, persistent notification ke saath.
6. Developer Options setup flow banao: AppOps se check karo ki RouteMock mock-location app select hua hai ya nahi. Nahi hua to guided screen dikhao aur Settings ke Developer Options par deep-link do.
7. Ek chhoti alag consumer test app banao (RouteMock ke andar nahi, alag test build) jo har location fix ko log kare: latitude, longitude, speed, bearing, time. Isse CSV me export karo.

**Pass criteria (sab zaroori hain):**
- Consumer test app me mock point dikhta hai aur route par chalta hai.
- Speed target se ±10% ke andar rehta hai.
- Bearing me achanak jump nahi aata.
- PAUSED me position freeze hoti hai aur har 2 second me wahi coordinate dobara inject hota hai.
- HOLDING (route complete) me final position par hold hota hai, real GPS par snap-back nahi hota.
- STOP dabane par real location wapas aati hai.

**Gate 1:** Agar ye pass nahi hota, to Phase 2 par mat jao. Engine ko pehle theek karo.

### Phase 2 — Map par Start → End route

1. Map par long-press se Start set karo. Doosre long-press (ya address search) se End set karo.
2. OSRM Route API se road-following route lao, distance dikhao, polyline map par banao.
3. Agar OSRM alternate routes de, to zyada se zyada 2 dikhao, user tap karke choose kare.
4. Polyline par long-press se via-point insert karo, route dobara fetch karo.
5. Address search: Nominatim se search karo, debounce + cache ke saath. Search result tap karne par wahi point Start/End bane.
6. API fail ya offline ho to: 2 baar retry with backoff, phir seedhi line (straight-line) wale path par banner dikhao — "Offline — following straight line". Ye PRD ka behaviour hai, chhupana nahi hai.

**Pass criteria:** Sheher ke andar 3 alag routes banao — chhota (1–2 km), medium (5–10 km), lamba (20 km+) — teeno road par chalte hain, building ke beech se nahi kat-te.

### Phase 3 — Duration, vehicle aur session controls

1. Route set hone ke baad sheet me dikhao: distance, duration field (minute me), vehicle choice (Car / Motorcycle / Bicycle) ya skip.
2. Recommendation formula: recommended_minutes = round((distance_km ÷ speed_kph) × 60). PRD speeds: Car 70, Motorcycle 50, Bicycle 10 km/h.
   - **Note, release se pehle review karna hai:** City route par 70 km/h unrealistic hai aur motorcycle ka car se slow hona samajh nahi aata. MVP me formula wahi rakho, lekin speeds ko final karne se pehle ek baar dobara decide karo.
3. User accept kare to field pre-fill ho, ya apna custom duration type kare. Duration par koi cap mat lagao (PRD ke hisaab se), lekin agar implied speed 130 km/h se zyada ho to non-blocking warning dikhao.
4. Session UI + notification controls: Pause / Resume, Stop (mid-route ho to confirmation ke saath), progress aur live ETA.
5. Edit remaining time: current position se nayi speed dobara calculate karo, ETA live update karo.
6. Tap-to-jump: path polyline par tap karne se mock position wahan jump kare aur wahan se continue kare.

**Pass criteria:** 10 km ka route, 12 minute me chalao — ETA aur progress sahi dikhte hain, pause/resume par position freeze hoti hai, edit time ke baad speed badal jaati hai, tap-to-jump ke baad route wahi se continue hota hai.

### Phase 4 — Freehand draw (road-snapped)

1. Draw mode toggle banao. User ungli se map par path banaye.
2. Draw karte waqt points ko batch karke OSRM Match/Nearest se road par snap karo. Har pixel par API call mat bhejo — points ko pehle simplify karo, phir batch bhejo.
3. MVP me snapping hamesha ON rahega, off-road toggle nahi hoga.
4. Draw khatam hone par final snapped polyline dikhao, distance nikalo, phir wahi duration/session flow chale jo Phase 3 me bana hai.

**Pass criteria:** Tedha-medha freehand path bhi road par snap hota hai, seedha building ya jungle ke beech se nahi kat-ta. Snapping ke baad path ki shape samajh me aati hai.

### Phase 5 — Library (favorites + history)

1. Favorites save karo: path + default duration + vehicle.
2. History: aakhri 50 sessions (path, duration, timestamp), one-tap re-run.
3. Billing abhi nahi hai, isliye Pro lock abhi enforce mat karo. Favorites ki limit wali logic code me ready rakhna hai, lekin unlock baad me aayega.
4. Agar time bache to Quick Settings tile add karo: last-used route bina app khole start/stop. Ye optional hai, core se zyada zaroori nahi.

**Pass criteria:** App band karke dobara kholo — favorites aur history wahi milte hain. History se re-run karne par wahi route wahi duration se chalta hai.

### Phase 6 — Hardening aur testing (isko skip mat karna)

1. Device matrix: Android 10, 12, 14, 16 par physical device + emulator par test karo. Mock behaviour har version par alag hota hai, khaas kar API 31+ isMock aur API 33+ completeness par.
2. Screen off test: 30 minute ka session screen off me chalao, phir 2 ghante ka soak test. Zero snap-back events hone chahiye.
3. Background test: App ko Recents se hatao, doosri app kholo, phir bhi session chalna chahiye (foreground service ki wajah se).
4. Battery saver test: Battery saver ON par session ka behaviour dekho, aur settings me battery-optimization exemption ki instructions do.
5. Service killed test: OS service kill kare to kya hota hai, notification tap se restart hota hai ya nahi — ye document karo, chhupao mat.
6. Tick-to-inject latency aur UI smoothness check karo: map draw karte waqt UI atakna nahi chahiye.
7. Firebase Test Lab par robo + pre-launch report har release candidate par.

**Pass criteria:** 2 ghante ka soak test bina snap-back ke pass hota hai, aur upar ke saare cases ke results likhe hue hain — kaunsa pass, kaunsa fail aur kyun.

### Phase 7 — Deployment ki tayyari (MVP ke baad, alag decision)

Ye MVP ka hissa nahi hai, lekin is phase ke liye MVP se data jama karna hai:

1. MVP testing ke dauran count karo: mahine me kitne route requests, kitne search requests aate hain. Isi se paid ka estimate banega, andaze se nahi.
2. Tab faisla karo: (a) OSRM self-host karo, (b) Google Maps free limit ke andar raho, ya (c) Mapbox ya koi paid provider lo.
3. Play Store listing sirf testing/QA/privacy wali language me likho. Games, cheat ya "undetectable" jaise words kabhi mat likhna.
4. Data safety form me declare karo: location data share nahi hota, sab on-device hai.
5. Naye developer account ke liye closed testing: 20 testers × 14 din. Isliye production rollout closed testing ke 14 din poore hone ke baad hi hoga — PRD ka 6 hafte wala plan is hisaab se week 8 ke aas paas jayega, ye pehle se maan lo.

## 6. Time estimate (sirf estimate hai, commitment nahi)

Ye estimate is baat par depend karta hai ki roz kitne ghante milte hain aur phone/OEM issues kitne nikalte hain:

| Phase | Kaam | Estimate |
|---|---|---|
| 0 | Setup + khaali map | 1–2 din |
| 1 | Engine proof + consumer test app | 4–7 din |
| 2 | Start → End route + search | 4–6 din |
| 3 | Duration/vehicle + session controls | 4–6 din |
| 4 | Freehand draw + snapping | 4–6 din |
| 5 | Favorites + history | 2–3 din |
| 6 | Hardening + full test matrix + soak | 5–7 din |
| **Total** | **MVP tak** | **Lagbhag 4–6 hafte ka kaam** |

Agar roz kaam nahi milta to calendar time isse zyada lagega. Engine (Phase 1) ya OEM battery issues atak sakte hain — wahi asli risk hai, map UI nahi.

## 7. MVP ke baad ke decisions (pehle se list kar lo)

1. Package name final karo (proposal: `com.routemock.app`).
2. Vehicle speeds final karo — PRD wali speeds ko city/highway ke hisaab se revise karna hai ya nahi.
3. Off-road toggle: v1 me lao ya Pro backlog me rakho (PRD open item #1).
4. Map vendor final karo: OSRM self-host, Google free limit, ya Mapbox (PRD open item #2 — MVP me ye OSM hai, deployment par revisit hoga).
5. Build kahan verify hoga: is workspace me Gradle ka pehle se issue raha hai (Nico ke time daemon fail hua tha), isliye RouteMock ka final APK test laptop/Android Studio par bhi karna padega.

## 8. Har phase ke baad ye 3 sawaal poochho

1. Kya ye consumer test app me dikh raha hai, ya sirf apne app ke andar lag raha hai?
2. Kya pause/hold me real GPS wapas aaya?
3. Kya ye wala kaam bina paid API ke ho raha hai?

Agar teeno ka jawaab haan hai, tabhi aage badho.
