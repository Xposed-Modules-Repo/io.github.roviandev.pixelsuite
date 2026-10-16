package io.github.pixelsuite;
final class HomeMenuPlacement {
 static int top(int anchor,int height,int safeTop,int safeBottom,int gap){
  int last=Math.max(safeTop,safeBottom-height);
  return Math.max(safeTop,Math.min(last,anchor-height-gap));
 }
}
