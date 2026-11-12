// Index page: sortable, filterable grid of pages from /index.json (ag-grid).
document.addEventListener("DOMContentLoaded", function () {
  const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const css = getComputedStyle(document.documentElement);
  const v = (name) => css.getPropertyValue(name).trim();
  const theme = agGrid.themeQuartz.withParams({
    backgroundColor: v("--page"),
    foregroundColor: v("--ink"),
    headerBackgroundColor: v("--paper-sunk"),
    borderColor: v("--rule"),
    accentColor: v("--pencil"),
    fontFamily: v("--sans"),
    fontSize: 13,
    rowHeight: 26,
    headerHeight: 30,
    browserColorScheme: dark ? "dark" : "light",
  });

  const escape = (s) => String(s).replace(/[&<>"]/g, (c) => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));

  const grid = agGrid.createGrid(document.getElementById("index-grid"), {
    theme: theme,
    columnDefs: [
      { field: "title", headerName: "Title", flex: 3, minWidth: 200,
        cellRenderer: (p) => `<a class="${p.data.kind === "stub" ? "stub" : ""}" href="${p.data.url}">${escape(p.data.title)}</a>`,
        comparator: (a, b) => a.localeCompare(b, undefined, { sensitivity: "base", numeric: true }) },
      { field: "kind", headerName: "Kind", width: 100 },
      { field: "modified", headerName: "Modified", width: 150, sort: "desc" },
      { field: "blocks", headerName: "Blocks", width: 100, type: "numericColumn" },
      { field: "refs", headerName: "Refs", width: 90, type: "numericColumn" },
    ],
    defaultColDef: { sortable: true, filter: true, resizable: true },
    pagination: true,
    paginationPageSize: 100,
    domLayout: "autoHeight",
    rowData: [],
  });

  document.getElementById("index-filter").addEventListener("input", (e) => {
    grid.setGridOption("quickFilterText", e.target.value);
  });

  fetch("/index.json")
    .then((r) => r.json())
    .then((rows) => grid.setGridOption("rowData", rows))
    .catch((err) => console.error("index.json:", err));
});
