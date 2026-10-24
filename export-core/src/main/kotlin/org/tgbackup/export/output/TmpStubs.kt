package org.tgbackup.export.output

class HtmlWriter(thumbs: ImageThumbs) : AbstractWriter by JsonWriter()
class HtmlAndJsonWriter(thumbs: ImageThumbs) : AbstractWriter by JsonWriter()
