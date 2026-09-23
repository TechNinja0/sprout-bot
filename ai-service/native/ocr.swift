import Foundation
import Vision
import AppKit

// 只从 stdin 接收图像，输出识别文本；会话帧不创建磁盘临时文件。
let data = FileHandle.standardInput.readDataToEndOfFile()
guard let img = NSImage(data: data), let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    fputs("invalid image\n", stderr); exit(2)
}
let req = VNRecognizeTextRequest()
req.recognitionLevel = .accurate
req.recognitionLanguages = ["zh-Hans", "en-US"]
req.usesLanguageCorrection = false
try VNImageRequestHandler(cgImage: cg).perform([req])
let rows = (req.results ?? []).compactMap { item -> [String: Any]? in
    guard let top = item.topCandidates(1).first else { return nil }
    return ["text":top.string, "confidence":top.confidence,
            "box":[item.boundingBox.minX,item.boundingBox.minY,item.boundingBox.width,item.boundingBox.height]]
}
let result:[String:Any] = ["text":rows.compactMap{$0["text"] as? String}.joined(separator:"\n"), "blocks":rows]
let encoded = try JSONSerialization.data(withJSONObject: result)
FileHandle.standardOutput.write(encoded)
