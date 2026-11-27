// ThirdMind site maps (design/static-site.md, phase 3). One graph for the whole site,
// graphs/map.json = {nodes: [{name, link, depth, entry}], nbrs: [[node index, ...], ...]},
// drawn with Vega as goddinpotty's per-page specs were: a page's map is its radius-2
// neighbourhood, at most 8 neighbours followed per node; the Map page is everything.

var thirdmindGraph = null;

function thirdmindLoad() {
    if (!thirdmindGraph) {
        thirdmindGraph = fetch("graphs/map.json").then(function (r) { return r.json(); });
    }
    return thirdmindGraph;
}

// Node indices within radius n of from (goddinpotty's multitool/neighborhood)
function thirdmindNeighborhood(g, from, n, maxDegree) {
    var seen = {};
    seen[from] = true;
    var frontier = [from];
    for (var step = 0; step < n; step++) {
        var next = [];
        frontier.forEach(function (i) {
            g.nbrs[i].slice(0, maxDegree).forEach(function (j) {
                if (!seen[j]) { seen[j] = true; next.push(j); }
            });
        });
        frontier = next;
    }
    return Object.keys(seen).map(Number).sort(function (a, b) { return a - b; });
}

function thirdmindSpec(g, members, isStart, o) {
    var pos = {};
    members.forEach(function (i, k) { pos[i] = k; });
    var nodes = members.map(function (i, k) {
        var d = g.nodes[i].depth || 0;
        return {name: g.nodes[i].name, link: g.nodes[i].link, index: k,
                group: isStart(i) ? 0 : 1,
                size: 50 + Math.pow(3 * (12 - d), 2)};   // the circle's area
    });
    var links = [];
    members.forEach(function (i) {
        g.nbrs[i].forEach(function (j) {
            if (j > i && pos[j] !== undefined) links.push({source: pos[i], target: pos[j]});
        });
    });
    return {
        $schema: "https://vega.github.io/schema/vega/v5.json",
        data: [{name: "node-data", values: nodes}, {name: "link-data", values: links}],
        autosize: "none", width: o.width, height: o.height, padding: 0,
        scales: [{name: "color", type: "ordinal", domain: {data: "node-data", field: "group"},
                  range: {scheme: "pastel1"}}],
        marks: [
            {name: "nodes", type: "symbol", zindex: 1, from: {data: "node-data"},
             on: [{trigger: "fix", modify: "node", values: "fix === true ? {fx: node.x, fy: node.y} : {fx: fix[0], fy: fix[1]}"},
                  {trigger: "!fix", modify: "node", values: "{fx: null, fy: null}"}],
             encode: {enter: {fill: {scale: "color", field: "group"}, href: {field: "link"},
                              stroke: {value: "white"}, strokeWidth: {value: 0}, size: {field: "size"}}},
             transform: [{type: "force", iterations: 300, restart: {signal: "restart"},
                          static: {signal: "static"}, signal: "force",
                          forces: [{force: "center", x: {signal: "cx"}, y: {signal: "cy"}},
                                   {force: "collide", radius: {signal: "nodeRadius"}},
                                   {force: "nbody", strength: {signal: "nodeCharge"}},
                                   {force: "link", links: "link-data", distance: {signal: "linkDistance"}}]}]},
            {name: "nodelabels", type: "text", from: {data: "nodes"}, zindex: 2,
             encode: {enter: {text: {field: "datum.name"}, x: {signal: "datum.x + 2 + sqrt(datum.size)/2"},
                              y: {signal: "datum.y + 3"}, fill: {value: "black"}},
                      update: {x: {signal: "datum.x + 2 + sqrt(datum.size)/2"}, y: {signal: "datum.y + 3"}}}},
            {type: "path", from: {data: "link-data"}, interactive: false,
             encode: {update: {stroke: {value: "gray"},
                               strokeWidth: {signal: "datum.source === node || datum.target === node ? 2 : 0.5"}}},
             transform: [{type: "linkpath", require: {signal: "force"}, shape: "line",
                          sourceX: "datum.source.x", sourceY: "datum.source.y",
                          targetX: "datum.target.x", targetY: "datum.target.y"}]}],
        signals: [
            {name: "cx", update: "width / 2"}, {name: "cy", update: "height / 2"},
            {name: "nodeRadius", value: o.nodeRadius}, {name: "nodeCharge", value: o.nodeCharge},
            {name: "linkDistance", value: o.linkDistance}, {name: "static", value: false},
            {name: "fix", value: false,
             on: [{events: "symbol:mouseout[!event.buttons], window:mouseup", update: "false"},
                  {events: "symbol:mouseover", update: "fix || true"},
                  {events: "[symbol:mousedown, window:mouseup] > window:mousemove!", update: "xy()", force: true}]},
            {name: "node", value: null, on: [{events: "symbol:mouseover", update: "fix === true ? item() : node"}]},
            {name: "restart", value: false, on: [{events: {signal: "fix"}, update: "fix && fix.length"}]}]
    };
}

// The sidebar map for the page whose link (slug) is center
function thirdmindPageMap(el, center) {
    thirdmindLoad().then(function (g) {
        var c = g.nodes.findIndex(function (n) { return n.link === center; });
        if (c < 0) return;                                    // eg a page left off the map
        var members = thirdmindNeighborhood(g, c, 2, 8);
        vegaEmbed(el, thirdmindSpec(g, members, function (i) { return i === c; },
                                    {width: 290, height: 350, linkDistance: 65, nodeCharge: -60, nodeRadius: 25}),
                  {actions: false});
    });
}

// The whole site, entry pages highlighted (the Map page)
function thirdmindFullMap(el) {
    thirdmindLoad().then(function (g) {
        var members = g.nodes.map(function (_, i) { return i; });
        vegaEmbed(el, thirdmindSpec(g, members, function (i) { return g.nodes[i].entry; },
                                    {width: 1000, height: 1000, linkDistance: 60, nodeCharge: -100, nodeRadius: 20}),
                  {actions: false});
    });
}
