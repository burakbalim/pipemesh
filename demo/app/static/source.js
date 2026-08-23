/* The files behind the demo, fetched from the process that is running them. */

const groups = document.getElementById("groups");

/* Opened by default, because a page of collapsed headings hides the one thing
 * this page exists to show. */
const OPEN_BY_DEFAULT = 1;

fetch("/api/source")
  .then((reply) => reply.json())
  .then((bundle) => bundle.groups.forEach(render))
  .catch(() => {
    const failed = document.createElement("div");
    failed.className = "failed";
    failed.textContent = "The source could not be read from the server.";
    groups.append(failed);
  });

function render(group) {
  const section = document.createElement("section");
  section.className = "group";
  section.innerHTML = `<h2></h2><p class="note"></p>`;
  section.querySelector("h2").textContent = group.title;
  section.querySelector(".note").textContent = group.note;

  group.files.forEach((file, index) => section.append(panel(file, index < OPEN_BY_DEFAULT)));
  groups.append(section);
}

function panel(file, open) {
  const element = document.createElement("div");
  element.className = "file";
  if (open) element.setAttribute("open-file", "");

  element.innerHTML = `<button class="path mono" type="button"></button><pre class="mono"></pre>`;
  element.querySelector(".path").textContent = file.path;
  element.querySelector("pre").textContent = file.body;
  element.querySelector(".path").onclick = () =>
    element.toggleAttribute("open-file");

  return element;
}
