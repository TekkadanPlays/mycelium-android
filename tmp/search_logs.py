import sys
import io

def search_logs(file_path, out_path):
    keywords = ["freeze", "slow", "OOM", "OutOfMemory", "skip", "stall", "exception", "block", "Choreographer", "ANR"]
    encodings_to_try = ["utf-8", "utf-16-le", "utf-16-be", "cp1252"]
    
    with io.open(out_path, "w", encoding="utf-8") as outf:
        for encoding in encodings_to_try:
            try:
                with io.open(file_path, "r", encoding=encoding) as f:
                    content = f.read(100) # try reading 100 char
                    outf.write(f"Successfully read with {encoding}\n")
                    f.seek(0)
                    for i, line in enumerate(f):
                        line_lower = line.lower()
                        for kw in keywords:
                            if kw.lower() in line_lower:
                                outf.write(f"{i+1}: {line.strip()}\n")
                                break
                break # if successful, break the outer loop
            except UnicodeDecodeError:
                outf.write(f"Failed with {encoding}\n")

if __name__ == "__main__":
    search_logs(sys.argv[1], "tmp/search_output.txt")
