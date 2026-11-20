const $ = (id) => document.getElementById(id)

const brandBytes = Uint8Array.from(atob($('brand-image').content.textContent), (character) => character.charCodeAt(0))
const brandUrl = URL.createObjectURL(new Blob([brandBytes], {type: 'image/png'}))
$('metro-mark').addEventListener('load', () => URL.revokeObjectURL(brandUrl), {once: true})
$('metro-mark').src = brandUrl
const list = (value) => Array.isArray(value) ? value : []
const clean = (value) => typeof value === 'string' ? value.trim() : ''
const has = (value) => {
  if (Array.isArray(value)) return value.some(has)
  if (value && typeof value === 'object') return Object.values(value).some(has)
  return typeof value === 'string' ? Boolean(value.trim()) : Boolean(value)
}

function element(tag, label = '', className = '') {
  const node = document.createElement(tag)
  node.textContent = label
  node.className = className
  return node
}

function button(label, action, className = 'soft') {
  const node = element('button', label, className)
  node.type = 'button'
  node.addEventListener('click', action)
  return node
}

const demo = {
  source: '演示路线',
  start: '购物公园',
  end: '上梅林',
  seconds: 0,
  legs: [
    {line: '1号线', color: '#00ab4f', direction: '罗湖', opposite: '机场东', from: '购物公园', to: '会展中心',
      stops: ['会展中心'], complete: true, walk: []},
    {line: '4号线', color: '#cc0000', direction: '牛湖', opposite: '福田口岸', from: '会展中心', to: '上梅林',
      stops: ['市民中心', '少年宫', '莲花北', '上梅林'], complete: true, walk: []},
  ],
  finalWalk: [],
}

function parseRoutes(data, start, end) {
  if (String(data?.status) !== '1' || !Array.isArray(data?.route?.transits)) {
    throw new Error('请选择有效的高德 Web 服务公交路径规划 JSON（v3）。')
  }
  return data.route.transits.flatMap((transit) => {
    const legs = []
    let walk = []
    let exit = ''
    for (const segment of list(transit?.segments)) {
      if (!segment || has(segment.railway) || has(segment.taxi)) return []
      walk.push(...list(segment.walking?.steps).map((step) => clean(step?.instruction)).filter(Boolean))
      if (!walk.length && Number(segment.walking?.distance) > 0) {
        walk.push('步行约 ' + Number(segment.walking.distance) + ' 米，沿现场指引前往。')
      }
      const lines = list(segment.bus?.buslines)
      if (!lines.length && has(segment.bus)) {
        // AMap uses empty arrays and objects for absent subfields.
        if (Object.keys(segment.bus).some((key) => key !== 'buslines')) return []
      }
      if (lines.length) {
        const rail = lines.find((line) => line && /地铁/.test(clean(line.type)) &&
          clean(line.name) && clean(line.departure_stop?.name) && clean(line.arrival_stop?.name))
        if (!rail) return []
        const name = rail.name.replace(/（/g, '(').replace(/）/g, ')')
        const line = name.split('(')[0]
        const ends = [...name.matchAll(/\(([^()]*)\)/g)].map((match) =>
          match[1].split(/\s*(?:[-–—→－]+|⇀)\s*/)).filter((pair) => pair.length > 1).at(-1) || []
        const from = clean(rail.departure_stop.name)
        const to = clean(rail.arrival_stop.name)
        const via = list(rail.via_stops).map((stop) => clean(stop?.name))
        const complete = via.every(Boolean) && /^\d+$/.test(String(rail.via_num)) &&
          via.length === Number(rail.via_num)
        const lineName = line.replace(/^地铁\s*/, '').trim()
        legs.push({line: lineName, color: colorForLine(lineName), direction: ends.length > 1 ? ends.at(-1) : '',
          opposite: ends.length > 1 ? ends[0] : '', from, to,
          stops: complete ? [...via, to] : [to], complete, walk, entrance: clean(segment.entrance?.name)})
        walk = []
        exit = ''
      }
      exit = clean(segment.exit?.name) || exit
    }
    if (!legs.length) return []
    return [{source: '高德路线', start: start || legs[0].from, end: end || legs.at(-1).to,
      seconds: Number(transit.duration) || 0, legs, finalWalk: walk, exit}]
  })
}

const offlineText = $('offline-data').content.textContent.trim()
const offline = Boolean(offlineText)
const android = location.origin === 'https://appassets.androidplatform.net' ? window.MetroAndroid : null
let current = null
let photoUrl = ''
let ready = false
let work = false
const picked = {origin: null, destination: null}
const options = $('options')

const network = JSON.parse($('network-data').content.textContent || '[]')
const stations = new Map()

function colorForLine(name) {
  return network.find((line) => line.name.split('/').includes(name))?.color
}

function lineBadge(leg) {
  const badge = element('span', leg.line, 'line-badge')
  if (!/^#[0-9a-f]{6}$/i.test(leg.color)) return badge
  const channels = leg.color.slice(1).match(/../g).map((hex) => parseInt(hex, 16) / 255)
  const linear = channels.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  const luminance = 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
  badge.style.backgroundColor = leg.color
  badge.style.color = luminance > 0.179 ? '#000' : '#fff'
  return badge
}

for (const line of network) {
  for (const [name, x, y, location, pinyin] of line.stops) {
    if (!stations.has(name)) stations.set(name, {name, x, y, location, pinyin, lines: []})
    stations.get(name).lines.push(line)
  }
}

function stationName(value) {
  return clean(value).replace(/[（(]地铁站[)）]$|地铁站$/, '').trim()
}

function routeByNetwork(origin, destination) {
  // Each graph node is a station on one line. Changing lines has a fixed cost.
  const transferCost = 4
  const byStation = new Map([...stations.keys()].map((name) => [name, []]))
  const byLine = new Map(network.map((line) => [line, line.stops.map(([name], index) => {
    const node = {name, line, index}
    byStation.get(name).push(node)
    return node
  })]))
  const open = new Map(byStation.get(origin.name).map((node) => [node, 0]))
  const settled = new Set()
  const previous = new Map()
  let end
  while (open.size) {
    const [node, cost] = [...open].reduce((best, entry) => entry[1] < best[1] ? entry : best)
    open.delete(node)
    if (node.name === destination.name) {
      end = node
      break
    }
    settled.add(node)
    const along = byLine.get(node.line)
    const neighbors = [along[node.index - 1], along[node.index + 1], ...byStation.get(node.name)]
    for (const next of neighbors) {
      if (!next || settled.has(next)) continue
      const distance = cost + (node.line === next.line ? 1 : transferCost)
      if (distance >= (open.get(next) ?? Infinity)) continue
      open.set(next, distance)
      previous.set(next, node)
    }
  }
  if (!end) throw new Error('本地线网中没有连接这两站的路线。')
  const path = []
  for (let node = end; node; node = previous.get(node)) path.push(node)
  path.reverse()
  const legs = []
  let first = path[0]
  for (let index = 1; index <= path.length; index += 1) {
    if (path[index]?.line === first.line) continue
    const last = path[index - 1]
    const forward = last.index > first.index
    const names = first.line.stops.map(([name]) => name)
    const stops = forward ? names.slice(first.index + 1, last.index + 1) :
      names.slice(last.index, first.index).reverse()
    if (stops.length) legs.push({line: first.line.name, from: first.name, to: last.name,
      direction: forward ? names.at(-1) : names[0], opposite: forward ? names[0] : names.at(-1),
      color: first.line.color, stops, complete: true, walk: []})
    first = path[index]
  }
  return {source: '本地线网', stationOnly: true, start: origin.name, end: destination.name,
    seconds: 0, legs, finalWalk: []}
}

const mapDialog = $('station-map')
let mapField = 'origin'
let mapScale = 1
let mapContent

function svgNode(tag, attributes = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag)
  for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, value)
  return node
}

function setMapWidth(width, keepCenter = false) {
  const view = $('map-view')
  const prior = mapContent.getAttribute('width') || width
  const x = (view.scrollLeft + view.clientWidth / 2) / prior
  const y = (view.scrollTop + view.clientHeight / 2) / prior
  mapContent.setAttribute('width', width)
  if (keepCenter) {
    view.scrollLeft = x * width - view.clientWidth / 2
    view.scrollTop = y * width - view.clientHeight / 2
  }
}

function renderMap() {
  const filter = $('map-line').value
  const lines = filter ? network.filter((line) => line.name === filter) : network
  const visible = new Map(lines.flatMap((line) => line.stops.map(([name]) => [name, stations.get(name)])))
  const points = lines.flatMap((line) => line.geometry)
  const left = Math.min(...points.map(([x]) => x)) - 48
  const top = Math.min(...points.map(([, y]) => y)) - 42
  const width = Math.max(...points.map(([x]) => x)) - left + 48
  const height = Math.max(...points.map(([, y]) => y)) - top + 42
  mapContent = svgNode('svg', {viewBox: [left, top, width, height].join(' '), 'aria-label': '深圳地铁线路示意图'})
  for (const line of lines) {
    mapContent.append(svgNode('polyline', {points: line.geometry.map((point) => point.join(',')).join(' '),
      fill: 'none', stroke: line.color, 'stroke-width': 5, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'}))
  }
  for (const station of visible.values()) {
    const selected = Object.values(picked).some((place) => place?.name === station.name)
    const interchange = station.lines.length > 1
    const dot = svgNode('g', {transform: 'translate(' + station.x + ' ' + station.y + ')',
      class: 'map-station', role: 'button', tabindex: 0, 'data-station': station.name,
      'aria-label': station.name + '，' + station.lines.map((line) => line.name).join('、')})
    dot.append(svgNode('circle', {r: selected ? 9 : interchange ? 7 : 5, fill: selected ? '#196849' : '#fff',
      stroke: interchange || selected ? '#152b24' : station.lines[0].color, 'stroke-width': 2.5}))
    const label = svgNode('text', {x: 0, y: -10})
    label.textContent = station.name
    dot.append(label, svgNode('circle', {r: 12, fill: 'transparent'}))
    dot.addEventListener('click', () => chooseMapStation(station))
    dot.addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' && event.key !== ' ') return
      event.preventDefault()
      chooseMapStation(station)
    })
    mapContent.append(dot)
  }
  $('map-view').replaceChildren(mapContent)
  setMapWidth(Math.round((filter ? Math.max(610, width * 0.75) : 1400) * mapScale))
}

function refreshMapPrompt() {
  $('map-prompt').textContent = '请点选' + (mapField === 'origin' ? '起点站' : '终点站')
  $('map-origin').setAttribute('aria-pressed', String(mapField === 'origin'))
  $('map-destination').setAttribute('aria-pressed', String(mapField === 'destination'))
  $('map-origin').textContent = '起点 · ' + (picked.origin?.name || '未选')
  $('map-destination').textContent = '终点 · ' + (picked.destination?.name || '未选')
}

function chooseMapStation(station) {
  const selectedField = mapField
  setStation(selectedField, station)
  if (selectedField === 'origin' && !picked.destination) {
    mapField = 'destination'
    refreshMapPrompt()
    renderMap()
  } else {
    mapDialog.close()
    $(selectedField === 'origin' ? 'destination' : 'plan-button').focus()
  }
}

function openMap(field) {
  mapField = field
  mapScale = 1
  $('map-line').value = ''
  refreshMapPrompt()
  renderMap()
  mapDialog.showModal()
  const view = $('map-view')
  view.scrollLeft = (view.scrollWidth - view.clientWidth) / 2
  view.scrollTop = (view.scrollHeight - view.clientHeight) / 2
}

function initMap() {
  const view = $('map-view')
  let drag
  view.addEventListener('pointerdown', (event) => {
    if (event.pointerType !== 'mouse' || event.button !== 0) return
    if (event.clientX >= view.getBoundingClientRect().left + view.clientWidth ||
        event.clientY >= view.getBoundingClientRect().top + view.clientHeight) return
    drag = {x: event.clientX, y: event.clientY, left: view.scrollLeft, top: view.scrollTop, active: false}
  })
  view.addEventListener('pointermove', (event) => {
    if (!drag || !(event.buttons & 1)) return
    const dx = event.clientX - drag.x
    const dy = event.clientY - drag.y
    if (!drag.active && Math.hypot(dx, dy) < 6) return
    if (!drag.active) {
      drag.active = true
      view.setPointerCapture(event.pointerId)
      view.classList.add('is-panning')
    }
    view.scrollTo(drag.left - dx, drag.top - dy)
  })
  const finishDrag = () => {
    drag = undefined
    view.classList.remove('is-panning')
  }
  view.addEventListener('pointerup', finishDrag)
  view.addEventListener('pointercancel', finishDrag)
  view.addEventListener('lostpointercapture', finishDrag)
  for (const line of network) $('map-line').append(new Option(line.name, line.name))
  $('map-line').addEventListener('change', () => {
    mapScale = 1
    renderMap()
    $('map-view').scrollTo(0, 0)
  })
  for (const field of ['origin', 'destination']) {
    $('map-' + field).addEventListener('click', () => {
      mapField = field
      refreshMapPrompt()
    })
  }
  for (const [id, factor] of [['map-zoom-in', 1.25], ['map-zoom-out', 0.8]]) {
    $(id).addEventListener('click', () => {
      const next = Math.min(3, Math.max(0.6, mapScale * factor))
      setMapWidth(Math.round(Number(mapContent.getAttribute('width')) * next / mapScale), true)
      mapScale = next
    })
  }
}


async function api(path, data) {
  let request
  if (data !== undefined) {
    const body = JSON.stringify(data)
    request = android ? {headers: {'X-Metro-Request': btoa(String.fromCharCode(...new TextEncoder().encode(body)))}} :
      {method: 'POST', headers: {'Content-Type': 'application/json'}, body}
  }
  const response = await fetch('/api/' + path, request)
  const result = await response.json()
  if (!response.ok) throw new Error(result.error || '请求失败，请重试。')
  return result
}

function status(id, message = '', error = false) {
  const panel = $(id)
  panel.hidden = !message
  panel.textContent = message
  panel.classList.toggle('error', error)
}

function setReady(value) {
  ready = value
  $('key-open').textContent = ready ? '高德已连接' : '高德设置'
  $('key-open').classList.toggle('is-ready', ready)
}

function showChoices(routes, includeAmap = false) {
  if (!routes.length) throw new Error('高德未返回只含地铁和步行的路线。请更换起点或终点试试。')
  const panel = $('choices')
  panel.replaceChildren(element('h2', '选择路线'))
  for (const route of routes) {
    const stops = route.legs.reduce((count, leg) => count + leg.stops.length, 0)
    const choice = button('', () => startTrip(route), 'soft choice')
    const badges = element('span', '', 'choice-lines')
    for (const leg of route.legs) {
      if (badges.childElementCount) badges.append(element('span', ' → '))
      badges.append(lineBadge(leg))
    }
    choice.append(badges)
    const estimate = route.seconds > 0 ? ' · 高德约 ' + Math.ceil(route.seconds / 60) + ' 分钟' : ' · ' + stops + ' 站'
    const transfers = route.legs.slice(1).map((leg) => leg.from)
    const connection = transfers.length ? '换乘 ' + transfers.length + ' 次（' + transfers.join('、') + '）' : '直达'
    choice.append(element('small', route.start + ' → ' + route.end + ' · ' + connection + estimate))
    panel.append(choice)
  }
  if (includeAmap) {
    panel.append(element('p', '本地线网按站数和换乘规划。请留意现场运营和列车方向。', 'route-note'))
    if (ready) {
      const {origin, destination} = picked
      const control = button('查看高德方案', async () => {
        control.disabled = true
        status('plan-status', '正在查询高德方案…')
        try {
          const online = await queryAmap(origin, destination, true)
          if (picked.origin !== origin || picked.destination !== destination || !$('trip').hidden) return
          if (online.length) showChoices([...routes, ...online])
          status('plan-status', online.length ? '' : '高德暂无这两站间的方案，可继续使用本地路线。')
        } catch (error) {
          if (picked.origin === origin && picked.destination === destination) {
            status('plan-status', error.message + ' 可继续使用本地路线。', true)
          }
        } finally {
          control.disabled = false
        }
      }, 'quiet')
      panel.append(control)
    }
  }
  panel.hidden = false
  panel.querySelector('button').focus()
}

function setStation(field, station) {
  picked[field] = station
  $(field).value = station.name
  $(field + '-field').querySelector('.suggestions').hidden = true
  $('choices').hidden = true
  status('plan-status')
}

function lookup(field, focusResult = false) {
  const input = $(field)
  const suggestions = $(field + '-field').querySelector('.suggestions')
  const word = stationName(input.value).toLowerCase().replace(/\s/g, '')
  suggestions.replaceChildren()
  if (!word) {
    suggestions.hidden = true
    if (focusResult) {
      status('plan-status', '请输入站名，或在地图上选站。', true)
      input.focus()
    }
    return
  }
  const matches = [...stations.values()].filter((station) => station.name.includes(word) ||
    station.pinyin.replace(/\s/g, '').includes(word))
  matches.sort((a, b) => Number(b.name === word) - Number(a.name === word) ||
    Number(b.name.startsWith(word)) - Number(a.name.startsWith(word)))
  for (const station of matches.slice(0, 8)) {
    const choice = button(station.name, () => {
      setStation(field, station)
      $(field === 'origin' ? 'destination' : 'plan-button').focus()
    }, 'soft')
    choice.append(element('small', station.lines.map((line) => line.name).join(' · ')))
    suggestions.append(choice)
  }
  suggestions.hidden = !matches.length
  if (!matches.length) status('plan-status', '没有匹配的深圳地铁站，可换个站名或用地图选择。', true)
  if (focusResult) suggestions.querySelector('button')?.focus()
}

function queryRoutes() {
  for (const field of ['origin', 'destination']) {
    if (!picked[field]) {
      const station = stations.get(stationName($(field).value))
      if (!station) return lookup(field, true)
      setStation(field, station)
    }
  }
  const {origin, destination} = picked
  if (origin === destination) throw new Error('起点和终点相同，请更换其中一站。')
  showChoices([routeByNetwork(origin, destination)], true)
  status('plan-status')
}

async function queryAmap(origin, destination, stationOnly = false) {
  if (!ready) {
    $('key-dialog').showModal()
    return []
  }
  const body = {origin: origin.location, destination: destination.location}
  const parse = (data) => {
    const routes = parseRoutes(data, origin.name, destination.name)
    if (!stationOnly) return routes
    return routes.filter((route) => stationName(route.legs[0].from) === origin.name &&
      stationName(route.legs.at(-1).to) === destination.name).map((route) => ({...route, stationOnly}))
  }
  let routes = parse(await api('route', body))
  if (!routes.length) routes = parse(await api('route', {...body, strategy: '2'}))
  return routes
}


function readUri(text) {
  const url = new URL(text)
  if (url.protocol !== 'https:' || url.hostname !== 'uri.amap.com' || url.pathname !== '/navigation') {
    throw new Error('请使用带起终点的高德 URI。App 分享短链目前无法读取，可直接输入起终点。')
  }
  const point = (param) => {
    const [lon, lat, ...name] = (url.searchParams.get(param) || '').split(',')
    if (!lon || !lat || !Number.isFinite(Number(lon)) || !Number.isFinite(Number(lat))) {
      throw new Error('链接缺少有效的起终点坐标。')
    }
    const location = Number(lon).toFixed(6) + ',' + Number(lat).toFixed(6)
    return {name: name.join(',').trim() || location, location}
  }
  return {origin: point('from'), destination: point('to')}
}

async function importRoute(raw) {
  const text = raw.trim().replace(/^\uFEFF/, '')
  if (!text || text.length > 2_000_000) throw new Error('请输入路线链接或小于 2 MB 的 JSON。')
  if (text.startsWith('{')) {
    let data
    try {
      data = JSON.parse(text)
    } catch {
      throw new Error('JSON 格式有误，请复制完整的路线结果。')
    }
    showChoices(parseRoutes(data, '', ''))
    status('plan-status')
    return
  }
  let points
  try {
    points = readUri(text)
  } catch (error) {
    if (error instanceof TypeError) throw new Error('请输入高德路线 URI 或公交路径规划 JSON。')
    throw error
  }
  if (!ready) {
    $('key-dialog').showModal()
    return
  }
  showChoices(await queryAmap(points.origin, points.destination))
  status('plan-status')
}

async function runPlan(action, control) {
  if (work) return
  work = true
  control.disabled = true
  $('choices').hidden = true
  status('plan-status', '正在处理…')
  try {
    await action()
    if ($('plan-status').textContent === '正在处理…') status('plan-status')
  } catch (error) {
    status('plan-status', error.message || '暂时无法完成操作。', true)
    $('plan-status').scrollIntoView({block: 'nearest'})
  } finally {
    work = false
    control.disabled = false
  }
}

function stepsFor(route) {
  return [
    ...route.legs.flatMap((leg, index) => [
      {kind: 'board', leg, index}, {kind: 'ride', leg, index},
    ]),
    ...(route.stationOnly ? [] : [{kind: 'finish'}]),
  ]
}

function startTrip(route) {
  clearPhoto()
  current = {
    route, steps: stepsFor(route), index: 0, paused: false, stopped: false,
    lead: 2, sound: false, vibration: true, stationIndex: -1, alerted: false,
  }
  $('planner').hidden = true
  $('trip').hidden = false
  $('key-open').hidden = true
  status('trip-status')
  renderStep()
}

function showTrip() {
  $('planner').hidden = true
  $('trip').hidden = false
  $('key-open').hidden = true
  renderStep()
}

function showPlanner(keepTrip) {
  if (!keepTrip) current = null
  clearPhoto()
  $('trip').hidden = true
  $('planner').hidden = false
  $('choices').hidden = true
  status('trip-status')
  $('key-open').hidden = false
  $('return-trip').hidden = !current
  $('origin').focus()
}

function clearPhoto() {
  if (photoUrl) URL.revokeObjectURL(photoUrl)
  photoUrl = ''
}

function advance() {
  clearPhoto()
  current.index += 1
  current.stationIndex = -1
  current.alerted = false
  status('trip-status')
  renderStep()
}

function retreat() {
  clearPhoto()
  current.index -= 1
  current.stationIndex = -1
  current.alerted = false
  status('trip-status')
  renderStep()
}

function setHeading(panel, kicker, title, lead = '', leg) {
  const sign = element('div', '', 'step-sign')
  const action = element('p', '', 'kicker')
  if (leg) action.append(lineBadge(leg))
  action.append(kicker)
  const heading = element('h1', title)
  heading.id = 'step-title'
  heading.tabIndex = -1
  sign.append(action, heading)
  if (lead) sign.append(element('p', lead, 'lead'))
  const body = element('div', '', 'step-body')
  panel.append(sign, body)
  return body
}

function addDetails(panel, label, lines) {
  if (!lines.length) return
  const details = element('details')
  details.append(element('summary', label))
  for (const line of lines) details.append(element('p', line))
  panel.append(details)
}

const stationSources = {
  '会展中心': {
    url: 'https://www.szmc.net/szmc_m/shentieyunying/yunyingzixun/wuzhangaichengchezhinan/line1/',
    text: '运营方资料列出了 4 号线往牛湖和往福田口岸两处站台方向。按现场标识确认方向。',
  },
}

function boarding(card, step) {
  const {leg, index} = step
  const title = index ? '换乘 ' + leg.line : '乘坐 ' + leg.line
  const lead = leg.direction ? '往 ' + leg.direction + ' 方向' :
    leg.complete ? '看站台屏，确认下一站是 ' + leg.stops[0] : '方向数据缺失，请在站内核对列车停站。'
  const panel = setHeading(card, index ? '在 ' + leg.from + ' 换乘' : '到 ' + leg.from + ' 上车', title, lead, leg)
  panel.append(element('p', '在 ' + leg.to + ' 下车。'))
  panel.append(button('我已上车', advance, 'primary'))
  const details = element('details')
  details.append(element('summary', '核对站内标识'))
  details.append(element('p', '把站牌的线路和方向与上方提示对照。'))
  const seen = element('select')
  seen.setAttribute('aria-label', '你看到的线路和方向')
  seen.append(new Option('选择你看到的标识', ''))
  if (leg.direction) seen.append(new Option(leg.line + ' · 往 ' + leg.direction, 'ok'))
  if (leg.opposite && leg.opposite !== leg.direction) {
    seen.append(new Option(leg.line + ' · 往 ' + leg.opposite, 'opposite'))
  }
  seen.append(new Option('其他线路或方向', 'other'))
  seen.addEventListener('change', () => {
    if (!seen.value) return status('trip-status')
    if (seen.value === 'ok') return status('trip-status', '已核对，可以等车。')
    status('trip-status', '和路线预期不一致。请查看其他站台或重新规划。', true)
  })
  details.append(seen)
  details.append(button('标识不一致', () => {
    status('trip-status', '停在当前步骤。请查看对侧站台或其他方向的标识，也可从行程选项重新规划。', true)
  }))
  const photo = element('input')
  photo.type = 'file'
  photo.accept = 'image/*'
  photo.setAttribute('capture', 'environment')
  photo.id = 'station-photo'
  const label = element('label', '拍摄或选取站牌照片')
  label.htmlFor = photo.id
  const preview = element('img', '', 'photos')
  preview.alt = '用于对照的站牌照片'
  preview.hidden = !photoUrl
  if (photoUrl) preview.src = photoUrl
  photo.addEventListener('change', () => {
    clearPhoto()
    preview.removeAttribute('src')
    preview.hidden = true
    const file = photo.files[0]
    if (!file) return
    if (!file.type.startsWith('image/') || file.size > 20_000_000) {
      status('trip-status', '请选择小于 20 MB 的图片。', true)
      return
    }
    photoUrl = URL.createObjectURL(file)
    preview.src = photoUrl
    preview.hidden = false
  })
  details.append(label, photo, preview, element('small', '照片只用于本页目视核对。'))
  panel.append(details)
  addDetails(panel, index ? '查看换乘步行指引' : '查看进站步行指引',
    [...leg.walk, ...(leg.entrance ? ['高德建议入口 ' + leg.entrance] : [])])
  if (leg.from === '会展中心' && leg.line === '4号线') {
    const source = stationSources[leg.from]
    const info = element('details')
    const link = element('a', '深圳地铁无障碍乘车指南')
    link.href = source.url
    link.target = '_blank'
    link.rel = 'noopener noreferrer'
    info.append(element('summary', '会展中心站参考'), element('p', source.text),
      element('small', '来源查阅于 2026-11-20'), link)
    panel.append(info)
  }
}

let audioContext

function playAlert() {
  if (current.vibration && typeof navigator.vibrate === 'function') navigator.vibrate([220, 100, 260])
  if (current.sound && audioContext?.state === 'running') {
    const tone = audioContext.createOscillator()
    const gain = audioContext.createGain()
    gain.gain.value = 0.12
    tone.frequency.value = 740
    tone.connect(gain).connect(audioContext.destination)
    tone.start()
    tone.stop(audioContext.currentTime + 0.32)
  }
}

function riding(card, leg) {
  const lead = current.lead
  const stationIndex = current.stationIndex
  const remaining = leg.stops.length - 1 - stationIndex
  const alert = remaining > 0 && remaining <= lead && leg.complete
  const panel = setHeading(card, '乘车中', '在 ' + leg.to + ' 下车',
    remaining === 0 ? '已确认到站，请下车。' : '听车内报站，确认实际到达的站。', leg)
  if (leg.complete) {
    if (alert) panel.append(element('p', '还有 ' + remaining + ' 站，准备下车。', 'station alert'))
    if (remaining > 0) {
      const label = element('label', '点选当前站')
      label.htmlFor = 'at-station'
      const select = element('select')
      select.id = 'at-station'
      for (const [index, name] of [leg.from, ...leg.stops].entries()) {
        select.append(new Option(name, String(index - 1)))
      }
      select.value = String(stationIndex)
      select.addEventListener('change', () => {
        current.stationIndex = Number(select.value)
        const left = leg.stops.length - 1 - current.stationIndex
        if (left > current.lead) current.alerted = false
        if (!current.alerted && current.lead > 0 && left <= current.lead) {
          playAlert()
          current.alerted = true
        }
        renderStep('at-station')
      })
      panel.append(label, select)
    }
  } else {
    panel.append(element('p', '高德未返回完整站序，请以车内报站为准。', 'station'))
  }
  const next = button('我已下车', () => {
    if (leg.complete && current.stationIndex !== leg.stops.length - 1) {
      confirmEarly('你在 ' + leg.to + ' 下车了吗？', advance)
    } else {
      advance()
    }
  }, 'primary')
  panel.append(next)
  if (leg.complete) {
    const details = element('details')
    const label = element('label', '提前量')
    label.htmlFor = 'reminder-lead'
    const select = element('select')
    select.id = 'reminder-lead'
    for (const [index, name] of ['关闭提前提醒', '提前 1 站', '提前 2 站', '提前 3 站'].entries()) {
      select.append(new Option(name, String(index)))
    }
    select.value = String(lead)
    select.addEventListener('change', () => {
      current.lead = Number(select.value)
      current.alerted = remaining > 0 && remaining <= current.lead
      renderStep('reminder-lead', true)
    })
    const settings = element('fieldset')
    for (const [field, name] of [['vibration', '振动（浏览器支持时）'], ['sound', '提示音']]) {
      const option = element('label')
      const input = element('input')
      input.type = 'checkbox'
      input.checked = current[field]
      input.addEventListener('change', async () => {
        current[field] = input.checked
        if (field === 'sound' && input.checked) {
          try {
            audioContext ||= new AudioContext()
            await audioContext.resume()
          } catch {
            status('trip-status', '此浏览器无法启用提示音，仍会显示文字提醒。')
          }
        }
      })
      option.append(input, ' ' + name)
      settings.append(option)
    }
    details.append(element('summary', '提醒设置'), element('p', '只有你点选实际到达的站时，页面才判断是否提醒。'),
      label, select, settings)
    panel.append(details)
  }
}

function confirmEarly(message, action) {
  const dialog = element('dialog')
  const controls = element('div', '', 'actions')
  controls.append(button('确认', () => {
    dialog.close()
    action()
  }, 'primary'), button('返回', () => dialog.close(), 'quiet'))
  dialog.append(element('h2', message), controls)
  dialog.addEventListener('close', () => dialog.remove(), {once: true})
  document.body.append(dialog)
  dialog.showModal()
}

function renderStep(focusId = 'step-title', reminderOpen = false) {
  const panel = $('step')
  panel.replaceChildren()
  panel.className = 'card step'
  const {route, index, steps, paused, stopped} = current
  $('trip-label').textContent = route.source + ' · ' + Math.min(index + 1, steps.length) + ' / ' + steps.length
  $('progress-fill').max = steps.length
  $('progress-fill').value = Math.min(index + 1, steps.length)
  const step = steps[index]
  panel.style.borderTopColor = step?.leg?.color || ''
  $('skip').hidden = !step || paused || stopped
  $('pause').hidden = !step || stopped
  $('pause').textContent = paused ? '继续导航' : '暂停导航'
  $('restart').hidden = !offline
  $('change').hidden = offline
  $('export').hidden = offline
  if (stopped) {
    const body = setHeading(panel, '行程已结束', '已退出导航')
    body.append(button('从头开始', () => startTrip(route), 'primary'))
  } else if (paused) {
    const body = setHeading(panel, '导航已暂停', '休息一下')
    body.append(button('继续导航', () => {
      current.paused = false
      renderStep()
    }, 'primary'))
  } else if (!step) {
    const body = setHeading(panel, '行程完成', '到达 ' + route.end)
    body.append(button(offline ? '从头开始' : '规划新路线',
      () => offline ? startTrip(route) : showPlanner(false), 'primary'))
  } else if (step.kind === 'board') {
    boarding(panel, step)
  } else if (step.kind === 'ride') {
    riding(panel, step.leg)
  } else {
    const body = setHeading(panel, '最后一步', route.finalWalk.length ? '步行到 ' + route.end : '出站',
      route.exit ? '高德建议出口 ' + route.exit : '按现场标识选择出口。')
    body.append(button('我已到达', advance, 'primary'))
    addDetails(body, '查看出站步行指引', route.finalWalk)
  }
  if (step && index > 0 && !paused && !stopped) {
    panel.querySelector('.step-body').append(button('上一步', retreat, 'quiet'))
  }
  if (reminderOpen && panel.querySelector('details')) panel.querySelector('details').open = true
  const focusTarget = $(focusId) || panel.querySelector('h1')
  focusTarget.focus({preventScroll: true})
}

async function downloadTrip() {
  const route = current.route
  const [html, style, script] = await Promise.all(['/', '/style.css', '/app.js'].map(async (url) => {
    const res = await fetch(url)
    if (!res.ok) throw new Error('离线行程暂时无法生成。')
    return res.text()
  }))
  const data = JSON.stringify(route).replace(/[&<>]/g, (char) =>
    '\\u' + char.charCodeAt(0).toString(16).padStart(4, '0'))
  const file = html
    .replace(/<template id="network-data">[\s\S]*?<\/template>/, '<template id="network-data"></template>')
    .replace('<template id="offline-data"></template>', () => '<template id="offline-data">' + data + '</template>')
    .replace('<link rel="stylesheet" href="/style.css">', () => '<style>' + style + '</style>')
    .replace('<script type="module" src="/app.js"></script>', () =>
      '<script defer type="module">' + script.replace(/<\/script/gi, '<\\/script') + '</script>')
  if (android) {
    const result = await new Promise((resolve) => {
      window.finishAndroidExport = (value) => {
        delete window.finishAndroidExport
        resolve(value)
      }
      android.saveOffline(file)
    })
    if (result === 'error') throw new Error('离线行程保存失败。')
    return result === 'saved'
  }
  const url = URL.createObjectURL(new Blob([file], {type: 'text/html;charset=utf-8'}))
  const link = element('a')
  link.href = url
  link.download = '深圳地铁单步导航-离线行程.html'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
  return true
}

for (const control of document.querySelectorAll('dialog .close')) {
  control.addEventListener('click', () => control.closest('dialog').close())
}
$('trip-menu').addEventListener('click', () => options.showModal())
$('pause').addEventListener('click', () => {
  options.close()
  current.paused = !current.paused
  status('trip-status')
  renderStep()
})
$('skip').addEventListener('click', () => {
  options.close()
  confirmEarly('跳过这一步，继续行程？', advance)
})
$('change').addEventListener('click', () => {
  options.close()
  picked.origin = null
  $('origin').value = ''
  $('origin-field').querySelector('.suggestions').hidden = true
  showPlanner(true)
})
$('restart').addEventListener('click', () => {
  options.close()
  clearPhoto()
  startTrip(current.route)
})
$('exit').addEventListener('click', () => {
  options.close()
  confirmEarly('退出当前行程？', () => {
    if (!offline) return showPlanner(false)
    clearPhoto()
    current.stopped = true
    status('trip-status')
    renderStep()
  })
})
$('export').addEventListener('click', async () => {
  options.close()
  try {
    if (await downloadTrip()) {
      status('trip-status', android ? '已保存离线行程 HTML。' : '已生成离线 HTML。可把文件传到手机，在浏览器中打开。')
    }
  } catch (error) {
    status('trip-status', error.message, true)
  }
})
if (android) {
  window.handleAndroidBack = () => {
    const dialog = document.querySelector('dialog[open]')
    if (dialog) {
      dialog.close()
      return true
    }
    if (!$('trip').hidden) {
      options.showModal()
      return true
    }
    return false
  }
}
if (offline) {
  startTrip(JSON.parse(offlineText))
} else {
  initMap()
  $('demo-button').addEventListener('click', () => startTrip(demo))
  $('return-trip').addEventListener('click', showTrip)
  $('key-open').addEventListener('click', () => $('key-dialog').showModal())
  $('key-form').addEventListener('submit', async (event) => {
    event.preventDefault()
    const form = event.currentTarget
    const control = form.querySelector('[type="submit"]')
    control.disabled = true
    status('key-status', '正在验证 Key…')
    try {
      const key = $('key').value.trim()
      await api('key', {key})
      setReady(true)
      status('key-status')
      $('key-dialog').close()
      status('plan-status', '高德已连接。规划后可查看高德方案。')
    } catch (error) {
      status('key-status', error.message.includes('10009') ?
        'Key 类型与接口不符（10009）。请在高德控制台添加「Web 服务」Key。' : error.message, true)
    } finally {
      $('key').value = ''
      control.disabled = false
    }
  })
  $('key-dialog').addEventListener('close', () => $('key').value = '')
  $('plan-form').addEventListener('submit', (event) => {
    event.preventDefault()
    runPlan(queryRoutes, $('plan-button'))
  })
  for (const field of ['origin', 'destination']) {
    $(field).addEventListener('input', () => {
      picked[field] = null
      $('choices').hidden = true
      $(field + '-field').querySelector('.suggestions').hidden = true
      status('plan-status')
      lookup(field)
    })
    $(field + '-field').querySelector('.open-map').addEventListener('click', () => openMap(field))
  }
  $('import-button').addEventListener('click', () =>
    runPlan(() => importRoute($('import-text').value), $('import-button')))
  $('import-file').addEventListener('change', (event) => {
    const file = event.currentTarget.files[0]
    if (!file) return
    event.currentTarget.value = ''
    runPlan(async () => {
      if (file.size > 2_000_000) throw new Error('请选择小于 2 MB 的 JSON 文件。')
      await importRoute(await file.text())
    }, $('import-button'))
  })
  api('status').then((data) => setReady(data.configured)).catch(() => setReady(false))
}
