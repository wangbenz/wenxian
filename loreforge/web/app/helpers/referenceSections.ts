export type ReferenceSection = { id: string; title: string; text: string };
/** 显式段首转换为阅读章节，正文原样保留；代码块不作标题解析。 */
export function referenceSections(text = ""): ReferenceSection[] {
  const sections: ReferenceSection[] = [];
  let title = "概述";
  let lines: string[] = [];
  let fence = "";
  let boundary = true;
  const append = () => {
    const body = lines.join("\n").trim();
    if (body)
      sections.push({
        id: `reading-section-${sections.length + 1}`,
        title,
        text: body,
      });
    lines = [];
  };
  for (const line of text.split(/\r?\n/)) {
    const codeFence = line.match(/^\s*(`{3,}|~{3,})/);
    if (codeFence) {
      if (!fence) fence = codeFence[1][0];
      else if (fence === codeFence[1][0]) fence = "";
      lines.push(line);
      boundary = false;
      continue;
    }
    const heading = !fence ? line.match(/^#{2,3}\s+(.+?)\s*#*$/) : null;
    const label =
      !fence && boundary ? line.match(/^\*\*([^*]{1,40})\*\*\s*(.+)$/) : null;
    if (heading || label) {
      append();
      title = (heading ? heading[1] : label![1]).replace(/[。．：:]$/, "");
      if (label) lines.push(label[2]);
    } else lines.push(line);
    boundary = !line.trim();
  }
  append();
  return sections;
}
